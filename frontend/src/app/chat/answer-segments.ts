import { Citation } from './chat.model';

/** A run of plain text, or a reference to one cited source. */
export type AnswerSegment = { kind: 'text'; text: string } | { kind: 'citation'; citation: Citation };

const MARKER = /\[\s*(\d{1,3}(?:\s*,\s*\d{1,3})*)\s*]/g;

/**
 * Splits an answer into text and citation segments, so `[2]` can be rendered as a clickable chip.
 * Only markers that resolve to a citation become chips; anything else stays text. The text is
 * always rendered as text (never as HTML or Markdown), so a document cannot inject markup, scripts or
 * auto-loading images into the page through the model's answer.
 */
export function answerSegments(content: string, citations: readonly Citation[]): AnswerSegment[] {
  const bySource = new Map(citations.map((c) => [c.sourceNumber, c]));
  const segments: AnswerSegment[] = [];
  let last = 0;
  for (const match of content.matchAll(MARKER)) {
    const resolved = match[1]
      .split(',')
      .map((n) => bySource.get(Number(n.trim())))
      .filter((c): c is Citation => !!c);
    if (!resolved.length) {
      continue;
    }
    pushText(segments, content.slice(last, match.index));
    resolved.forEach((citation) => segments.push({ kind: 'citation', citation }));
    last = match.index + match[0].length;
  }
  pushText(segments, content.slice(last));
  return segments;
}

function pushText(segments: AnswerSegment[], text: string): void {
  if (text) {
    segments.push({ kind: 'text', text });
  }
}

/** "Handbook.pdf, page 3" or just "Notes.md" for unpaginated documents. */
export function citationLabel(citation: Citation): string {
  return citation.pageNumber ? `${citation.documentName}, page ${citation.pageNumber}` : citation.documentName;
}
