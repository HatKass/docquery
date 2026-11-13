import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { SessionWatch } from '../auth/session-watch.service';
import { SseParser } from '../shared/sse-parser';
import { DocumentStreamMessage, DocumentSummary } from './document.model';

/** `fetch`, injectable so tests can replace it. */
export const FETCH = new InjectionToken<typeof fetch>('FETCH', {
  providedIn: 'root',
  factory: () => globalThis.fetch.bind(globalThis),
});

/** Delays between reconnect attempts; the last one repeats. */
export const RECONNECT_DELAYS_MS = new InjectionToken<readonly number[]>('RECONNECT_DELAYS_MS', {
  providedIn: 'root',
  factory: () => [1_000, 2_000, 5_000, 10_000],
});

/** The stream was refused for good (signed out, or no longer a member): do not retry. */
export class StreamRejectedError extends Error {
  constructor(readonly status: number) {
    super(`Live updates rejected (${status})`);
  }
}

/**
 * Live document changes of a workspace via Server-Sent Events over `fetch`, so the access token goes
 * in the `Authorization` header (EventSource cannot do that). Reconnects with backoff when the
 * connection drops or the server ends the stream; every reconnect starts with a fresh token and is
 * announced by a new `ready` message, after which callers should re-sync their list.
 */
@Injectable({ providedIn: 'root' })
export class DocumentEvents {
  private readonly auth = inject(AuthService);
  private readonly fetch = inject(FETCH);
  private readonly session = inject(SessionWatch);
  private readonly delays = inject(RECONNECT_DELAYS_MS);

  connect(workspaceId: string): Observable<DocumentStreamMessage> {
    return new Observable<DocumentStreamMessage>((subscriber) => {
      let stopped = false;
      let controller: AbortController | null = null;
      let failures = 0;
      let timer: ReturnType<typeof setTimeout> | undefined;

      const sleep = (ms: number) => new Promise<void>((resolve) => (timer = setTimeout(resolve, ms)));

      const readStream = async (): Promise<void> => {
        controller = new AbortController();
        const token = this.auth.accessToken();
        if (!token) {
          throw new StreamRejectedError(401);
        }
        const response = await this.fetch(`/api/workspaces/${workspaceId}/documents/events`, {
          headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
          signal: controller.signal,
          cache: 'no-store',
        });
        if (response.status === 401) {
          void this.session.onUnauthorized();
        }
        if (response.status === 401 || response.status === 403 || response.status === 404) {
          throw new StreamRejectedError(response.status);
        }
        if (!response.ok || !response.body) {
          throw new Error(`Live updates unavailable (${response.status})`);
        }
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        const parser = new SseParser();
        while (!stopped) {
          const { value, done } = await reader.read();
          if (done) {
            return;
          }
          for (const event of parser.feed(value)) {
            const message = toMessage(event.event, event.data);
            if (message) {
              if (message.type === 'ready') {
                failures = 0;
              }
              subscriber.next(message);
            }
          }
        }
      };

      const run = async (): Promise<void> => {
        while (!stopped) {
          try {
            await readStream();
          } catch (error) {
            if (stopped) {
              return;
            }
            if (error instanceof StreamRejectedError) {
              subscriber.error(error);
              return;
            }
            failures++;
          }
          if (stopped) {
            return;
          }
          subscriber.next({ type: 'reconnecting' });
          await sleep(this.delays[Math.min(failures, this.delays.length - 1)]);
        }
      };

      void run();

      return () => {
        stopped = true;
        clearTimeout(timer);
        controller?.abort();
      };
    });
  }
}

function toMessage(event: string, data: string): DocumentStreamMessage | null {
  switch (event) {
    case 'ready':
      return { type: 'ready' };
    case 'document.created':
    case 'document.updated': {
      const payload = JSON.parse(data) as { document: DocumentSummary };
      return { type: event === 'document.created' ? 'created' : 'updated', document: payload.document };
    }
    case 'document.deleted': {
      const payload = JSON.parse(data) as { documentId: string };
      return { type: 'deleted', documentId: payload.documentId };
    }
    default:
      return null; // unknown events from a newer server are ignored
  }
}
