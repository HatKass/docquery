import { answerSegments } from './answer-segments';
import { Citation } from './chat.model';

/**
 * A safe Markdown subset for answers: paragraphs, headings (shown as bold lines), "-"/"*" and
 * numbered lists, **bold** and `inline code`, with `[n]` citation markers anywhere in them.
 *
 * The answer is parsed into this small tree and rendered with ordinary Angular bindings, never as
 * HTML: raw HTML, links and images in the model's output stay visible as plain text, so neither the
 * model nor a document it quotes can inject markup, scripts or auto-loading images into the page.
 */
export type Inline =
  | { kind: 'text'; text: string }
  | { kind: 'code'; text: string }
  | { kind: 'citation'; citation: Citation }
  | { kind: 'bold'; children: BoldChild[] };

/** Bold text can contain plain text and citations (no nesting beyond that). */
export type BoldChild = { kind: 'text'; text: string } | { kind: 'citation'; citation: Citation };

export type Block =
  | { kind: 'paragraph'; inlines: Inline[] }
  | { kind: 'heading'; inlines: Inline[] }
  | { kind: 'list'; ordered: boolean; start: number; items: Inline[][] };

const BULLET = /^\s*[-*•]\s+(.*)$/;
const NUMBERED = /^\s*(\d{1,3})[.)]\s+(.*)$/;
const HEADING = /^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$/;
const CODE = /`([^`\n]+)`/g;
const BOLD = /\*\*(.+?)\*\*|__(.+?)__/g;

export function formatAnswer(content: string, citations: readonly Citation[]): Block[] {
  const blocks: Block[] = [];
  let paragraph: string[] = [];
  let list: { ordered: boolean; start: number; items: string[] } | null = null;

  const flushParagraph = () => {
    if (paragraph.length) {
      blocks.push({ kind: 'paragraph', inlines: inlines(paragraph.join('\n'), citations) });
      paragraph = [];
    }
  };
  const flushList = () => {
    if (list) {
      blocks.push({
        kind: 'list',
        ordered: list.ordered,
        start: list.start,
        items: list.items.map((item) => inlines(item, citations)),
      });
      list = null;
    }
  };

  for (const line of content.replace(/\r\n?/g, '\n').split('\n')) {
    const bullet = BULLET.exec(line);
    const numbered = bullet ? null : NUMBERED.exec(line);
    const heading = HEADING.exec(line);
    if (!line.trim()) {
      flushParagraph();
      flushList();
    } else if (bullet || numbered) {
      flushParagraph();
      const ordered = !!numbered;
      if (!list || list.ordered !== ordered) {
        flushList();
        list = { ordered, start: numbered ? Number(numbered[1]) : 1, items: [] };
      }
      list.items.push(bullet ? bullet[1] : numbered![2]);
    } else if (heading) {
      flushParagraph();
      flushList();
      blocks.push({ kind: 'heading', inlines: inlines(heading[1], citations) });
    } else if (list && /^\s+\S/.test(line)) {
      // An indented line continues the last list item.
      list.items[list.items.length - 1] += ' ' + line.trim();
    } else {
      flushList();
      paragraph.push(line);
    }
  }
  flushParagraph();
  flushList();
  return blocks;
}

/** Inline code first (its content is literal), then bold, then citation markers in what is left. */
export function inlines(text: string, citations: readonly Citation[]): Inline[] {
  const result: Inline[] = [];
  let last = 0;
  for (const match of text.matchAll(CODE)) {
    result.push(...boldAndCitations(text.slice(last, match.index), citations));
    result.push({ kind: 'code', text: match[1] });
    last = match.index + match[0].length;
  }
  result.push(...boldAndCitations(text.slice(last), citations));
  return result;
}

function boldAndCitations(text: string, citations: readonly Citation[]): Inline[] {
  const result: Inline[] = [];
  let last = 0;
  for (const match of text.matchAll(BOLD)) {
    result.push(...answerSegments(text.slice(last, match.index), citations));
    const children = answerSegments(match[1] ?? match[2], citations);
    if (children.length) {
      result.push({ kind: 'bold', children });
    }
    last = match.index + match[0].length;
  }
  result.push(...answerSegments(text.slice(last), citations));
  return result;
}
