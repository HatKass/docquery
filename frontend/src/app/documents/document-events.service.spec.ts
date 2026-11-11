import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { DocumentEvents, FETCH, RECONNECT_DELAYS_MS, StreamRejectedError } from './document-events.service';
import { DocumentStreamMessage } from './document.model';

/** A Response whose body streams the given chunks, then ends. */
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

describe('DocumentEvents', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let token: string | null;

  beforeEach(() => {
    token = 'token-123';
    fetchMock = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: FETCH, useValue: fetchMock },
        { provide: RECONNECT_DELAYS_MS, useValue: [0] },
        { provide: AuthService, useValue: { accessToken: () => token } },
      ],
    });
  });

  function collect(count: number): Promise<{ messages: DocumentStreamMessage[]; error?: unknown }> {
    return new Promise((resolve) => {
      const messages: DocumentStreamMessage[] = [];
      const sub = TestBed.inject(DocumentEvents)
        .connect('ws1')
        .subscribe({
          next: (m) => {
            messages.push(m);
            if (messages.length === count) {
              sub.unsubscribe();
              resolve({ messages });
            }
          },
          error: (error) => resolve({ messages, error }),
        });
    });
  }

  it('sends the bearer token and maps server events to messages', async () => {
    const doc = { id: 'd1', workspaceId: 'ws1', filename: 'a.pdf', status: 'EXTRACTING' };
    fetchMock.mockResolvedValueOnce(
      sseResponse(
        'event:ready\ndata:{}\n\n',
        `event:document.updated\ndata:${JSON.stringify({ documentId: 'd1', document: doc })}\n\n:keep-alive\n\n`,
        'event:document.deleted\ndata:{"documentId":"d1"}\n\n',
      ),
    );

    const { messages } = await collect(3);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workspaces/ws1/documents/events',
      expect.objectContaining({ headers: { Authorization: 'Bearer token-123', Accept: 'text/event-stream' } }),
    );
    expect(messages).toEqual([
      { type: 'ready' },
      { type: 'updated', document: doc },
      { type: 'deleted', documentId: 'd1' },
    ]);
  });

  it('reconnects when the server ends the stream', async () => {
    fetchMock
      .mockResolvedValueOnce(sseResponse('event:ready\ndata:{}\n\n'))
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValue(sseResponse('event:ready\ndata:{}\n\n'));

    const { messages } = await collect(4);

    expect(messages.map((m) => m.type)).toEqual(['ready', 'reconnecting', 'reconnecting', 'ready']);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('gives up when access is denied', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 404 }));

    const { error } = await collect(99);

    expect(error).toBeInstanceOf(StreamRejectedError);
    expect((error as StreamRejectedError).status).toBe(404);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not connect without a token', async () => {
    token = null;
    const { error } = await collect(99);
    expect((error as StreamRejectedError).status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
