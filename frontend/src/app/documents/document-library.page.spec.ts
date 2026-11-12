import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { confirmDialog } from '../shared/testing/confirm-dialog.harness';
import { WorkspaceRole } from '../workspaces/workspace.model';
import { DocumentLibraryPage } from './document-library.page';
import { DocumentEvents } from './document-events.service';
import { DocumentStreamMessage, DocumentSummary } from './document.model';

const doc = (id: string, overrides: Partial<DocumentSummary> = {}): DocumentSummary => ({
  id,
  workspaceId: 'demo',
  filename: `${id}.pdf`,
  contentType: 'application/pdf',
  sizeBytes: 2_621_440,
  status: 'UPLOADED',
  failureReason: null,
  pageCount: null,
  chunkCount: null,
  uploadedBy: 'u1',
  uploadedByName: 'Max Member',
  uploadedAt: `2026-11-10T10:00:0${id.length}Z`,
  statusChangedAt: '2026-11-10T10:00:00Z',
  ...overrides,
});

const policy = {
  maxFileSizeBytes: 1_048_576,
  allowedExtensions: ['docx', 'md', 'pdf', 'txt'],
  allowedContentTypes: [],
  requeueAfterSeconds: 600,
};

const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

describe('DocumentLibraryPage', () => {
  let http: HttpTestingController;
  let stream: Subject<DocumentStreamMessage>;

  beforeEach(() => {
    localStorage.clear();
    stream = new Subject();
    TestBed.configureTestingModule({
      imports: [DocumentLibraryPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DocumentEvents, useValue: { connect: () => stream.asObservable() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const tick = () => new Promise((r) => setTimeout(r));

  async function open(role: WorkspaceRole, documents: DocumentSummary[]) {
    const fixture = TestBed.createComponent(DocumentLibraryPage);
    fixture.componentRef.setInput('workspaceId', 'demo');
    fixture.detectChanges();
    http.expectOne('/api/me').flush({
      id: 'u1',
      username: 'x',
      email: null,
      displayName: 'X',
      workspaces: [{ id: 'demo', name: 'Demo workspace', role, memberCount: 3, createdAt: '' }],
    });
    await tick();
    http.expectOne('/api/documents/upload-policy').flush(policy);
    http.expectOne('/api/workspaces/demo/documents').flush(documents);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el, rows: () => Array.from(el.querySelectorAll('[data-testid="document-table"] tbody tr')) };
  }

  it('lists documents with status, counts and size', async () => {
    const { el, rows } = await open('VIEWER', [
      doc('ready', { status: 'READY', pageCount: 12, chunkCount: 40 }),
      doc('bad', { status: 'FAILED', failureReason: 'The PDF is password-protected.' }),
    ]);
    expect(rows().length).toBe(2);
    expect(rows()[0].textContent).toContain('ready.pdf');
    expect(rows()[0].textContent).toContain('Ready');
    expect(rows()[0].textContent).toContain('12');
    expect(rows()[0].textContent).toContain('2.5 MB');
    expect(el.querySelector('[data-testid="failure-reason"]')?.textContent).toContain('password-protected');
  });

  it('hides upload and delete from viewers', async () => {
    const { el } = await open('VIEWER', [doc('a')]);
    expect(el.querySelector('[data-testid="upload-zone"]')).toBeNull();
    expect(el.querySelector('[data-testid="read-only-hint"]')).not.toBeNull();
    expect(el.textContent).not.toContain('Delete');
  });

  it('shows the upload zone with the limits to members', async () => {
    const { el } = await open('MEMBER', []);
    expect(el.querySelector('[data-testid="upload-zone"]')?.textContent).toContain('up to 1.0 MB');
    expect(el.querySelector<HTMLInputElement>('[data-testid="file-input"]')?.accept).toBe('.docx,.md,.pdf,.txt');
    expect(el.querySelector('[data-testid="no-documents"]')).not.toBeNull();
  });

  it('applies live status updates, new documents and deletions', async () => {
    const { fixture, el, rows } = await open('MEMBER', [doc('a')]);

    stream.next({ type: 'updated', document: doc('a', { status: 'EMBEDDING', pageCount: 3 }) });
    stream.next({ type: 'created', document: doc('newer') });
    await fixture.whenStable();
    expect(rows().map((r) => r.getAttribute('data-document-id'))).toEqual(['newer', 'a']);
    expect(rows()[1].querySelector('[data-testid="status"]')?.textContent).toContain('Embedding');

    stream.next({ type: 'deleted', documentId: 'newer' });
    await fixture.whenStable();
    expect(rows().length).toBe(1);

    stream.next({ type: 'reconnecting' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="stream-state"]')?.textContent).toContain('Reconnecting');
  });

  it('re-fetches the list whenever the stream (re)connects', async () => {
    const { fixture, el, rows } = await open('MEMBER', [doc('a')]);

    stream.next({ type: 'ready' });
    http.expectOne('/api/workspaces/demo/documents').flush([doc('a'), doc('missed')]);
    await tick();
    await fixture.whenStable();

    expect(rows().length).toBe(2);
    expect(el.querySelector('[data-testid="stream-state"]')?.textContent).toContain('Live');
  });

  it('uploads picked files and rejects obviously invalid ones without calling the API', async () => {
    const { fixture, el, rows } = await open('MEMBER', []);
    const page = fixture.componentInstance;

    const uploading = page.uploadAll([
      new File(['%PDF-1.4'], 'manual.pdf'),
      new File(['x'], 'photo.png'),
      new File([new Uint8Array(2_097_152)], 'huge.txt'),
    ]);
    const post = http.expectOne('/api/workspaces/demo/documents');
    expect(post.request.method).toBe('POST');
    expect((post.request.body as FormData).get('file')).toBeInstanceOf(File);
    post.flush(doc('manual'));
    await uploading;
    await fixture.whenStable();

    expect(rows().length).toBe(1);
    const pending = el.querySelector('[data-testid="pending-uploads"]')?.textContent ?? '';
    expect(pending).toContain('photo.png');
    expect(pending).toContain('Unsupported file type');
    expect(pending).toContain('huge.txt');
    expect(pending).toContain('the limit is 1.0 MB');
  });

  it('offers Retry for failed and Re-index for ready documents to members only', async () => {
    const { fixture, rows } = await open('MEMBER', [
      doc('ready', { status: 'READY', pageCount: 1, chunkCount: 2 }),
      doc('bad', { status: 'FAILED', failureReason: 'The embedding service was unavailable.' }),
      doc('busy', { status: 'EMBEDDING' }),
    ]);
    const action = (row: Element) => row.querySelector<HTMLButtonElement>('[data-testid="reindex"]');
    expect(action(rows()[0])?.textContent?.trim()).toBe('Re-index');
    expect(action(rows()[1])?.textContent?.trim()).toBe('Retry');
    expect(action(rows()[2])).toBeNull();

    action(rows()[1])!.click();
    const post = http.expectOne('/api/workspaces/demo/documents/bad/reindex');
    expect(post.request.method).toBe('POST');
    post.flush(doc('bad', { status: 'UPLOADED' }));
    await tick();
    await fixture.whenStable();

    expect(rows()[1].querySelector('[data-testid="status"]')?.textContent).not.toContain('Failed');
    expect(action(rows()[1])).toBeNull();
  });

  it('offers Retry for a document queued longer than the policy allows', async () => {
    const { fixture, rows } = await open('MEMBER', [
      doc('fresh', { status: 'UPLOADED', statusChangedAt: minutesAgo(2) }),
      doc('stuck', { status: 'UPLOADED', statusChangedAt: minutesAgo(11) }),
    ]);
    const action = (id: string) =>
      rows()
        .find((r) => r.getAttribute('data-document-id') === id)
        ?.querySelector<HTMLButtonElement>('[data-testid="reindex"]') ?? null;
    expect(action('fresh')).toBeNull();
    expect(action('stuck')?.textContent?.trim()).toBe('Retry');
    expect(action('stuck')?.title).toContain('Still queued');

    // Time passes: the fresh document becomes stuck too, without a reload.
    fixture.componentInstance['now'].set(Date.now() + 9 * 60_000);
    await fixture.whenStable();
    expect(action('fresh')?.textContent?.trim()).toBe('Retry');

    action('stuck')!.click();
    const post = http.expectOne('/api/workspaces/demo/documents/stuck/reindex');
    post.flush(doc('stuck', { status: 'UPLOADED', statusChangedAt: new Date().toISOString() }));
    await tick();
    await fixture.whenStable();
    fixture.componentInstance['now'].set(Date.now());
    await fixture.whenStable();
    expect(action('stuck')).toBeNull();
  });

  describe('deleting a document', () => {
    afterEach(() => confirmDialog.cleanUp());

    async function clickDelete(id: string) {
      const page = await open('MEMBER', [doc('keep'), doc('CIN', { filename: 'CIN.pdf', status: 'READY' })]);
      const row = page.rows().find((r) => r.getAttribute('data-document-id') === id)!;
      const button = row.querySelector<HTMLButtonElement>('[data-testid="delete-document"]')!;
      button.focus();
      button.click();
      await tick();
      return { ...page, button };
    }

    it('asks in a danger dialog naming the file and the consequence', async () => {
      const { button } = await clickDelete('CIN');

      expect(button.getAttribute('aria-label')).toBe('Delete CIN.pdf');
      expect(confirmDialog.variant()).toBe('danger');
      expect(confirmDialog.title()).toBe('Delete CIN.pdf?');
      expect(confirmDialog.message()).toContain('The file, its text chunks and embeddings will be permanently removed');
      expect(confirmDialog.accept().textContent?.trim()).toBe('Delete document');
      confirmDialog.cancel().click();
    });

    it('deletes after confirmation and removes the row', async () => {
      const { fixture, rows } = await clickDelete('CIN');

      confirmDialog.accept().click();
      const del = http.expectOne('/api/workspaces/demo/documents/CIN');
      expect(del.request.method).toBe('DELETE');
      del.flush(null);
      await tick();
      await fixture.whenStable();

      expect(confirmDialog.element()).toBeNull();
      expect(rows().map((r) => r.getAttribute('data-document-id'))).toEqual(['keep']);
    });

    it('keeps the document and returns focus to its button when cancelled', async () => {
      const { rows, button } = await clickDelete('CIN');

      confirmDialog.cancel().click();
      await tick();

      http.expectNone('/api/workspaces/demo/documents/CIN');
      expect(rows().length).toBe(2);
      expect(document.activeElement).toBe(button);
    });

    it('shows a failed delete inside the dialog instead of closing it', async () => {
      const { rows } = await clickDelete('CIN');

      confirmDialog.accept().click();
      http.expectOne('/api/workspaces/demo/documents/CIN').flush(
        { detail: 'This action requires the MEMBER role in this workspace; you are VIEWER.' },
        { status: 403, statusText: 'Forbidden' },
      );
      await tick();

      expect(confirmDialog.element()).not.toBeNull();
      expect(confirmDialog.error()).toContain('requires the MEMBER role');
      expect(rows().length).toBe(2);
      confirmDialog.cancel().click();
    });
  });

  it('never offers re-indexing to viewers', async () => {
    const { el } = await open('VIEWER', [
      doc('ready', { status: 'READY' }),
      doc('stuck', { status: 'UPLOADED', statusChangedAt: minutesAgo(60) }),
    ]);
    expect(el.querySelector('[data-testid="reindex"]')).toBeNull();
  });

  it('shows the API reason when an upload is rejected', async () => {
    const { fixture, el } = await open('MEMBER', []);

    const uploading = fixture.componentInstance.uploadAll([new File(['MZ'], 'invoice.pdf')]);
    http.expectOne('/api/workspaces/demo/documents').flush(
      { type: 'urn:docquery:problem:file-content-mismatch', detail: "The content of 'invoice.pdf' is not a valid PDF file." },
      { status: 415, statusText: 'Unsupported Media Type' },
    );
    await uploading;
    await fixture.whenStable();

    expect(el.querySelector('[data-testid="pending-uploads"] [role="alert"]')?.textContent).toContain('not a valid PDF');
  });
});
