export interface SseEvent {
  /** Event name; `message` when the server did not name it. */
  event: string;
  data: string;
}

/**
 * Incremental parser for the `text/event-stream` format (WHATWG HTML spec, "server-sent events").
 * Feed it decoded text chunks as they arrive; chunks may split lines and events anywhere.
 *
 * Used instead of `EventSource` because EventSource cannot send an `Authorization` header, and
 * putting access tokens in URLs would leak them into logs and browser history.
 */
export class SseParser {
  private buffer = '';
  private eventName = '';
  private dataLines: string[] = [];

  feed(chunk: string): SseEvent[] {
    this.buffer += chunk;
    const events: SseEvent[] = [];
    let newline: RegExpExecArray | null;
    const lineBreak = /\r\n|\r|\n/g;
    let consumed = 0;
    while ((newline = lineBreak.exec(this.buffer)) !== null) {
      // A lone trailing "\r" may be the first half of "\r\n": wait for more input.
      if (newline[0] === '\r' && newline.index === this.buffer.length - 1) {
        break;
      }
      const line = this.buffer.slice(consumed, newline.index);
      consumed = newline.index + newline[0].length;
      const event = this.processLine(line);
      if (event) {
        events.push(event);
      }
    }
    this.buffer = this.buffer.slice(consumed);
    return events;
  }

  private processLine(line: string): SseEvent | null {
    if (line === '') {
      return this.dispatch();
    }
    if (line.startsWith(':')) {
      return null; // comment / keep-alive
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'event') {
      this.eventName = value;
    } else if (field === 'data') {
      this.dataLines.push(value);
    }
    // "id" and "retry" are not used by DocQuery; unknown fields are ignored per spec.
    return null;
  }

  private dispatch(): SseEvent | null {
    const event = this.dataLines.length ? { event: this.eventName || 'message', data: this.dataLines.join('\n') } : null;
    this.eventName = '';
    this.dataLines = [];
    return event;
  }
}
