import { answerSegments, citationLabel } from './answer-segments';
import { Citation } from './chat.model';

const citation = (n: number, page: number | null = n): Citation => ({
  sourceNumber: n,
  documentId: `d${n}`,
  documentName: `Doc${n}.pdf`,
  contentType: 'application/pdf',
  pageNumber: page,
  snippet: `snippet ${n}`,
});

describe('answerSegments', () => {
  it('turns resolvable markers into citation segments', () => {
    const c1 = citation(1);
    const c2 = citation(2);

    expect(answerSegments('Leave is 24 days [1]. Claims: 30 days [1, 2].', [c1, c2])).toEqual([
      { kind: 'text', text: 'Leave is 24 days ' },
      { kind: 'citation', citation: c1 },
      { kind: 'text', text: '. Claims: 30 days ' },
      { kind: 'citation', citation: c1 },
      { kind: 'citation', citation: c2 },
      { kind: 'text', text: '.' },
    ]);
  });

  it('leaves unknown markers and markup as plain text', () => {
    expect(answerSegments('See [7] <img src=x onerror=alert(1)>', [citation(1)])).toEqual([
      { kind: 'text', text: 'See [7] <img src=x onerror=alert(1)>' },
    ]);
  });

  it('labels citations with their page when there is one', () => {
    expect(citationLabel(citation(3))).toBe('Doc3.pdf, page 3');
    expect(citationLabel(citation(4, null))).toBe('Doc4.pdf');
  });
});
