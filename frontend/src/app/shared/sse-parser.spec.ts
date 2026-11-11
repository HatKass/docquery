import { SseParser } from './sse-parser';

describe('SseParser', () => {
  it('parses named events with JSON data', () => {
    const events = new SseParser().feed('event:document.created\ndata:{"documentId":"d1"}\n\n');
    expect(events).toEqual([{ event: 'document.created', data: '{"documentId":"d1"}' }]);
  });

  it('handles events and lines split across chunks', () => {
    const parser = new SseParser();
    expect(parser.feed('event:rea')).toEqual([]);
    expect(parser.feed('dy\ndata:{}')).toEqual([]);
    expect(parser.feed('\n')).toEqual([]);
    expect(parser.feed('\n')).toEqual([{ event: 'ready', data: '{}' }]);
  });

  it('supports CRLF and a CRLF split between chunks', () => {
    const parser = new SseParser();
    expect(parser.feed('data: a\r')).toEqual([]);
    expect(parser.feed('\n\r\n')).toEqual([{ event: 'message', data: 'a' }]);
  });

  it('joins multi-line data, strips one leading space and ignores comments', () => {
    const events = new SseParser().feed(':keep-alive\n\nevent: x\ndata:  two spaces\ndata:line2\nid: 7\n\n');
    expect(events).toEqual([{ event: 'x', data: ' two spaces\nline2' }]);
  });

  it('does not dispatch an event without data', () => {
    expect(new SseParser().feed('event:lonely\n\n')).toEqual([]);
  });
});
