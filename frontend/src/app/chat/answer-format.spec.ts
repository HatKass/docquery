import { TestBed } from '@angular/core/testing';
import { AnswerContent } from './answer-content';
import { formatAnswer } from './answer-format';
import { Citation } from './chat.model';

const citation = (n: number): Citation => ({
  sourceNumber: n,
  documentId: `d${n}`,
  documentName: `Doc${n}.pdf`,
  contentType: 'application/pdf',
  pageNumber: n,
  snippet: `snippet ${n}`,
});

describe('formatAnswer', () => {
  it('parses paragraphs, headings, lists, bold, code and citations', () => {
    const c1 = citation(1);
    const c2 = citation(2);
    const blocks = formatAnswer(
      '**Facts:**\n- Java and **Spring Boot** [1]\n- Uses `mvn verify` [2]\n\n## Assessment\nSolid junior [1, 2].\n\n1. First\n2. Second',
      [c1, c2],
    );

    expect(blocks).toEqual([
      { kind: 'paragraph', inlines: [{ kind: 'bold', children: [{ kind: 'text', text: 'Facts:' }] }] },
      {
        kind: 'list',
        ordered: false,
        start: 1,
        items: [
          [
            { kind: 'text', text: 'Java and ' },
            { kind: 'bold', children: [{ kind: 'text', text: 'Spring Boot' }] },
            { kind: 'text', text: ' ' },
            { kind: 'citation', citation: c1 },
          ],
          [{ kind: 'text', text: 'Uses ' }, { kind: 'code', text: 'mvn verify' }, { kind: 'text', text: ' ' }, { kind: 'citation', citation: c2 }],
        ],
      },
      { kind: 'heading', inlines: [{ kind: 'text', text: 'Assessment' }] },
      {
        kind: 'paragraph',
        inlines: [{ kind: 'text', text: 'Solid junior ' }, { kind: 'citation', citation: c1 }, { kind: 'citation', citation: c2 }, { kind: 'text', text: '.' }],
      },
      { kind: 'list', ordered: true, start: 1, items: [[{ kind: 'text', text: 'First' }], [{ kind: 'text', text: 'Second' }]] },
    ]);
  });

  it('keeps code literal and leaves links and images as text', () => {
    expect(formatAnswer('Run `**not bold** [1]` and see [docs](https://evil.test) ![x](https://evil.test/p.png)', [citation(1)])).toEqual([
      {
        kind: 'paragraph',
        inlines: [
          { kind: 'text', text: 'Run ' },
          { kind: 'code', text: '**not bold** [1]' },
          { kind: 'text', text: ' and see [docs](https://evil.test) ![x](https://evil.test/p.png)' },
        ],
      },
    ]);
  });

  it('joins indented continuation lines into the list item', () => {
    const blocks = formatAnswer('- first line\n  continues here\n- second', []);
    expect(blocks).toEqual([
      {
        kind: 'list',
        ordered: false,
        start: 1,
        items: [[{ kind: 'text', text: 'first line continues here' }], [{ kind: 'text', text: 'second' }]],
      },
    ]);
  });
});

describe('AnswerContent', () => {
  function render(content: string, citations: Citation[] = []) {
    const fixture = TestBed.createComponent(AnswerContent);
    fixture.componentRef.setInput('content', content);
    fixture.componentRef.setInput('citations', citations);
    fixture.detectChanges();
    return fixture;
  }

  it('shows HTML in an answer as text, never as markup', () => {
    const fixture = render('<b>bold?</b> <img src=x onerror="alert(1)"> <script>alert(2)</script> <a href="https://evil.test">x</a>');
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('<b>bold?</b>');
    expect(el.textContent).toContain('<img src=x onerror="alert(1)">');
    expect(el.textContent).toContain('<script>alert(2)</script>');
    expect(el.querySelector('b, img, script, a')).toBeNull();
  });

  it('renders lists and bold with working citation chips inside list items', () => {
    const c1 = citation(1);
    const fixture = render('**Facts:**\n- Java [1]\n- **Angular** [1]', [c1]);
    const el = fixture.nativeElement as HTMLElement;
    const clicked: Citation[] = [];
    fixture.componentInstance.citationSelected.subscribe((c) => clicked.push(c));

    expect(el.querySelector('strong')?.textContent).toContain('Facts:');
    const items = el.querySelectorAll('ul > li');
    expect(items.length).toBe(2);
    expect(items[1].querySelector('strong')?.textContent).toContain('Angular');

    const chip = items[0].querySelector('[data-testid="citation-chip"]') as HTMLButtonElement;
    expect(chip.textContent?.trim()).toBe('1');
    chip.click();
    expect(clicked).toEqual([c1]);
  });
});
