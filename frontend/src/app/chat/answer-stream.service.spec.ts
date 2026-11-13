import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { FETCH } from '../documents/document-events.service';
import { AnswerStream, AskRejectedError } from './answer-stream.service';
import { AnswerStreamEvent } from './chat.model';

function sseResponse(...chunks: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((c) => controller.enqueue(encoder.encode(c)));
      controller.close();
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('AnswerStream', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: FETCH, useValue: fetchMock },
        { provide: AuthService, useValue: { accessToken: () => 'token-123' } },
      ],
    });
  });

  function collect(): Promise<{ events: AnswerStreamEvent[]; error?: unknown }> {
    return new Promise((resolve) => {
      const events: AnswerStreamEvent[] = [];
      TestBed.inject(AnswerStream)
        .ask('ws1', 'c1', 'How long is leave?')
        .subscribe({
          next: (e) => events.push(e),
          complete: () => resolve({ events }),
          error: (error) => resolve({ events, error }),
        });
    });
  }

  it('POSTs the question with the bearer token and maps the stream, even split mid-event', async () => {
    const answer = { id: 'a1', role: 'ASSISTANT', content: '24 days [1].', status: 'COMPLETE', createdAt: '', citations: [] };
    fetchMock.mockResolvedValueOnce(
      sseResponse(
        'event:start\ndata:{"question":{"id":"q1"},"answerId":"a1"}\n\n',
        'event:delta\ndata:{"text":"24 da',
        'ys "}\n\nevent:delta\ndata:{"text":"[1]."}\n\n',
        `event:done\ndata:${JSON.stringify({ answer })}\n\n`,
      ),
    );

    const { events, error } = await collect();

    expect(error).toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workspaces/ws1/conversations/c1/messages',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ question: 'How long is leave?' }),
        headers: expect.objectContaining({ Authorization: 'Bearer token-123' }),
      }),
    );
    expect(events).toEqual([
      { type: 'start', question: { id: 'q1' }, answerId: 'a1' },
      { type: 'delta', text: '24 days ' },
      { type: 'delta', text: '[1].' },
      { type: 'done', answer },
    ]);
  });

  it('reports a refused question with the API explanation', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: 'Questions can be at most 2000 characters long.' }), {
        status: 422,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );

    const { error } = await collect();

    expect(error).toBeInstanceOf(AskRejectedError);
    expect((error as AskRejectedError).status).toBe(422);
    expect((error as Error).message).toBe('Questions can be at most 2000 characters long.');
  });

  it('fails when the stream ends without done or error', async () => {
    fetchMock.mockResolvedValueOnce(sseResponse('event:start\ndata:{"question":{},"answerId":"a1"}\n\n'));

    const { events, error } = await collect();

    expect(events.map((e) => e.type)).toEqual(['start']);
    expect((error as Error).message).toContain('connection was lost');
  });

  it('aborts the request when unsubscribed', () => {
    fetchMock.mockReturnValueOnce(new Promise(() => undefined));
    const sub = TestBed.inject(AnswerStream).ask('ws1', 'c1', 'q').subscribe();

    sub.unsubscribe();

    const signal = (fetchMock.mock.calls[0]?.[1] as RequestInit | undefined)?.signal;
    expect(signal?.aborted).toBe(true);
  });
});
