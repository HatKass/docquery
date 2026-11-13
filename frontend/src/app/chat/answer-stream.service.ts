import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { SessionWatch } from '../auth/session-watch.service';
import { FETCH } from '../documents/document-events.service';
import { ProblemDetail } from '../shared/problem';
import { SseParser } from '../shared/sse-parser';
import { AnswerStreamEvent, ChatMessage } from './chat.model';

/** The question was refused before any answer started (validation, access, unknown conversation). */
export class AskRejectedError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ProblemDetail | null,
  ) {
    super(problem?.detail ?? `The question could not be sent (${status}).`);
  }
}

/**
 * Asks a question and streams the answer: Server-Sent Events over a `fetch` POST (the bearer token
 * goes in the header; EventSource can neither POST nor send headers). Unsubscribing aborts the
 * request, which makes the server stop the model.
 *
 * Unlike the document stream there is no automatic reconnect: a POST is not safe to repeat, and the
 * server stores the outcome (complete or failed) either way.
 */
@Injectable({ providedIn: 'root' })
export class AnswerStream {
  private readonly auth = inject(AuthService);
  private readonly fetch = inject(FETCH);
  private readonly session = inject(SessionWatch);

  ask(workspaceId: string, conversationId: string, question: string): Observable<AnswerStreamEvent> {
    return new Observable<AnswerStreamEvent>((subscriber) => {
      const controller = new AbortController();
      let finished = false;

      const run = async () => {
        const token = this.auth.accessToken();
        if (!token) {
          throw new AskRejectedError(401, null);
        }
        const response = await this.fetch(`/api/workspaces/${workspaceId}/conversations/${conversationId}/messages`, {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
            Accept: 'text/event-stream, application/problem+json',
          },
          body: JSON.stringify({ question }),
          signal: controller.signal,
          cache: 'no-store',
        });
        if (response.status === 401) {
          void this.session.onUnauthorized();
        }
        if (!response.ok || !response.body) {
          throw new AskRejectedError(response.status, await readProblem(response));
        }
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        const parser = new SseParser();
        for (;;) {
          const { value, done } = await reader.read();
          if (done) {
            break;
          }
          for (const event of parser.feed(value)) {
            const parsed = toEvent(event.event, event.data);
            if (parsed) {
              subscriber.next(parsed);
              finished ||= parsed.type === 'done' || parsed.type === 'error';
            }
          }
        }
        if (!finished) {
          throw new Error('The connection was lost before the answer was complete.');
        }
      };

      run().then(
        () => subscriber.complete(),
        (error: unknown) => {
          if (!controller.signal.aborted) {
            subscriber.error(error);
          }
        },
      );
      return () => controller.abort();
    });
  }
}

async function readProblem(response: Response): Promise<ProblemDetail | null> {
  try {
    return (await response.json()) as ProblemDetail;
  } catch {
    return null;
  }
}

function toEvent(event: string, data: string): AnswerStreamEvent | null {
  const payload = JSON.parse(data) as Record<string, unknown>;
  switch (event) {
    case 'start':
      return { type: 'start', question: payload['question'] as ChatMessage, answerId: payload['answerId'] as string };
    case 'delta':
      return { type: 'delta', text: payload['text'] as string };
    case 'done':
      return { type: 'done', answer: payload['answer'] as ChatMessage };
    case 'error':
      return { type: 'error', answer: (payload['answer'] as ChatMessage | null) ?? null, detail: payload['detail'] as string };
    default:
      return null; // unknown events from a newer server are ignored
  }
}
