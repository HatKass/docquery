import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { vi } from 'vitest';
import { DocumentOpener } from '../documents/document-opener.service';
import { QUOTAS } from '../usage/testing/usage-fixtures';
import { MyQuotas } from '../usage/usage.model';
import { AnswerStream } from './answer-stream.service';
import { AnswerStreamEvent, ChatMessage, Citation } from './chat.model';
import { ChatPage } from './chat.page';

const citation: Citation = {
  sourceNumber: 1,
  documentId: 'd1',
  documentName: 'Handbook.pdf',
  contentType: 'application/pdf',
  pageNumber: 4,
  snippet: 'Employees receive 24 days of paid vacation.',
};

const message = (id: string, role: 'USER' | 'ASSISTANT', content: string, citations: Citation[] = []): ChatMessage => ({
  id,
  role,
  content,
  status: 'COMPLETE',
  createdAt: '2026-11-12T10:00:00Z',
  citations,
});

describe('ChatPage', () => {
  let http: HttpTestingController;
  let stream: Subject<AnswerStreamEvent>;
  let ask: ReturnType<typeof vi.fn>;
  let openDocument: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.clear();
    stream = new Subject();
    ask = vi.fn(() => stream.asObservable());
    openDocument = vi.fn(() => Promise.resolve());
    TestBed.configureTestingModule({
      imports: [ChatPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AnswerStream, useValue: { ask } },
        { provide: DocumentOpener, useValue: { open: openDocument } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // The quota hint is refreshed after every answer; tests that do not look at it just answer it.
    http.match('/api/me/quotas').forEach((r) => r.flush(QUOTAS));
    http.verify();
  });

  const tick = () => new Promise((r) => setTimeout(r));

  async function open(conversations: { id: string; title: string }[] = [], messages: ChatMessage[] = [], quotas: MyQuotas = QUOTAS) {
    const fixture = TestBed.createComponent(ChatPage);
    fixture.componentRef.setInput('workspaceId', 'demo');
    fixture.detectChanges();
    http.expectOne('/api/me').flush({
      id: 'u1',
      username: 'x',
      email: null,
      displayName: 'X',
      admin: false,
      workspaces: [{ id: 'demo', name: 'Demo workspace', role: 'VIEWER', memberCount: 3, createdAt: '' }],
    });
    http.expectOne('/api/me/quotas').flush(quotas);
    await tick();
    http.expectOne('/api/workspaces/demo/documents').flush([{ id: 'd1', status: 'READY' }]);
    http.expectOne('/api/workspaces/demo/conversations').flush(
      conversations.map((c) => ({ ...c, workspaceId: 'demo', createdAt: '', updatedAt: '' })),
    );
    await tick();
    if (conversations.length) {
      http
        .expectOne(`/api/workspaces/demo/conversations/${conversations[0].id}`)
        .flush({ ...conversations[0], workspaceId: 'demo', createdAt: '', updatedAt: '', messages });
      await tick();
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement as HTMLElement;

  it('shows how many questions are left today', async () => {
    const fixture = await open();
    expect(el(fixture).querySelector('[data-testid="question-quota"]')?.textContent).toContain('38 of 50 questions left today');
  });

  it('says when the daily question limit is used up', async () => {
    const fixture = await open([], [], { ...QUOTAS, questionsToday: { used: 50, limit: 50, remaining: 0 } });
    expect(el(fixture).querySelector('[data-testid="question-quota"]')?.textContent).toContain('used all 50 questions');
  });

  it('shows the history of the most recent conversation with clickable citations', async () => {
    const fixture = await open(
      [{ id: 'c1', title: 'Leave' }],
      [message('q1', 'USER', 'How long is leave?'), message('a1', 'ASSISTANT', '24 days [1].', [citation])],
    );

    const answer = el(fixture).querySelector('[data-testid="answer"]')!;
    expect(answer.textContent).toContain('24 days');
    expect(el(fixture).querySelector('[data-testid="sources"]')!.textContent).toContain('[1] Handbook.pdf, page 4');

    (answer.querySelector('[data-testid="citation-chip"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const viewer = el(fixture).querySelector('[data-testid="citation-viewer"]')!;
    expect(viewer.querySelector('[data-testid="citation-document"]')!.textContent).toContain('Handbook.pdf');
    expect(viewer.querySelector('[data-testid="citation-page"]')!.textContent).toContain('Page 4');
    expect(viewer.querySelector('[data-testid="citation-snippet"]')!.textContent).toContain('24 days of paid vacation');

    const openButton = viewer.querySelector('[data-testid="open-citation"]') as HTMLButtonElement;
    expect(openButton.textContent).toContain('Open document at page 4');
    openButton.click();
    expect(openDocument).toHaveBeenCalledWith(
      'demo',
      { id: 'd1', filename: 'Handbook.pdf', contentType: 'application/pdf' },
      4,
    );
  });

  it('creates a conversation on the first question and streams the answer in', async () => {
    const fixture = await open();
    const input = el(fixture).querySelector('[data-testid="question-input"]') as HTMLTextAreaElement;
    input.value = 'How long is leave?';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (el(fixture).querySelector('[data-testid="send"]') as HTMLButtonElement).click();
    await tick();
    http.expectOne({ method: 'POST', url: '/api/workspaces/demo/conversations' }).flush({
      id: 'c9',
      workspaceId: 'demo',
      title: 'New conversation',
      createdAt: '',
      updatedAt: '',
    });
    await tick();
    expect(ask).toHaveBeenCalledWith('demo', 'c9', 'How long is leave?');

    stream.next({ type: 'start', question: message('q1', 'USER', 'How long is leave?'), answerId: 'a1' });
    stream.next({ type: 'delta', text: '24 days ' });
    stream.next({ type: 'delta', text: '[1].' });
    fixture.detectChanges();
    expect(el(fixture).querySelector('[data-testid="pending-answer"]')!.textContent).toContain('24 days [1].');
    expect(el(fixture).querySelector('[data-testid="stop"]')).not.toBeNull();

    stream.next({ type: 'done', answer: message('a1', 'ASSISTANT', '24 days [1].', [citation]) });
    stream.complete();
    fixture.detectChanges();
    http.expectOne('/api/workspaces/demo/conversations').flush([]);

    expect(el(fixture).querySelector('[data-testid="pending-answer"]')).toBeNull();
    expect(el(fixture).querySelectorAll('[data-testid="citation-chip"]').length).toBe(1);
    expect(input.value).toBe('');
  });

  it('shows the error when answering fails', async () => {
    const fixture = await open([{ id: 'c1', title: 'Leave' }]);
    const input = el(fixture).querySelector('[data-testid="question-input"]') as HTMLTextAreaElement;
    input.value = 'Anything?';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el(fixture).querySelector('[data-testid="send"]') as HTMLButtonElement).click();
    await tick();

    stream.next({ type: 'start', question: message('q1', 'USER', 'Anything?'), answerId: 'a1' });
    stream.next({
      type: 'error',
      answer: { ...message('a1', 'ASSISTANT', 'The language model is not available right now.'), status: 'FAILED' },
      detail: 'The language model is not available right now.',
    });
    fixture.detectChanges();

    expect(el(fixture).querySelector('[data-testid="chat-error"]')!.textContent).toContain('not available');
    expect(el(fixture).querySelector('[data-testid="answer"]')!.getAttribute('data-status')).toBe('FAILED');
  });
});
