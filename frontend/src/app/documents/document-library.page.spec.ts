import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
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

const policy = { maxFileSizeBytes: 1_048_576, allowedExtensions: ['docx', 'md', 'pdf', 'txt'], allowedContentTypes: [] };

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
