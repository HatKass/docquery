import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { confirmDialog } from '../shared/testing/confirm-dialog.harness';
import { REPORT } from '../usage/testing/usage-fixtures';
import { WorkspaceContext } from '../workspaces/workspace-context.service';
import { adminGuard } from './admin.guard';
import { AdminPage } from './admin.page';
import { AdminUser, DeadLetter } from './admin.model';

const settle = () => new Promise((r) => setTimeout(r));

const deadLetter: DeadLetter = {
  id: 'dl1',
  status: 'PENDING',
  eventId: 'e1',
  documentId: 'd1',
  documentName: 'broken.pdf',
  documentStatus: 'FAILED',
  workspaceId: 'w1',
  workspaceName: 'Pipeline',
  exceptionClass: 'dev.docquery.worker.ingestion.EmbeddingUnavailableException',
  errorSummary: 'Connection refused',
  exceptionMessage:
    "Listener method 'void dev.docquery.worker.ingestion.DocumentUploadedListener.onDocumentUploaded(ConsumerRecord)' threw exception; Connection refused",
  originalTopic: 'document.uploaded',
  originalPartition: 1,
  originalOffset: 42,
  failedAt: '2026-11-13T10:00:00Z',
  resolvedAt: null,
  resolvedBy: null,
  retryable: true,
  payload: '{"documentId":"d1"}',
};

const user: AdminUser = {
  id: 'u9',
  username: 'max',
  displayName: 'Max Member',
  email: null,
  lastSeenAt: '',
  workspacesCreated: 3,
  workspaceLimitOverride: null,
  workspaceLimit: 3,
  questionsToday: 7,
  uploadsToday: 1,
};

describe('AdminPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AdminPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    confirmDialog.cleanUp();
  });

  /** Answers every request the page makes on (re)load. */
  function flushLoad(deadLetters: DeadLetter[] = [deadLetter], users: AdminUser[] = [user]) {
    http.expectOne('/api/admin/overview').flush({
      documentsByStatus: {
        UPLOADED: 1,
        EXTRACTING: 0,
        CHUNKING: 0,
        EMBEDDING: 0,
        READY: 9,
        FAILED: 1,
      },
      pendingDeadLetters: deadLetters.length,
      users: 4,
      workspaces: 2,
      defaultWorkspaceLimit: 3,
    });
    http.expectOne('/api/admin/usage?days=30').flush({
      ...REPORT,
      workspaces: [{ workspaceId: 'w1', name: null, answers: 3, tokens: 1, estimatedCost: 0.0042 }],
    });
    http.expectOne('/api/admin/failures').flush([
      {
        documentId: 'd1',
        workspaceId: 'w1',
        workspaceName: 'Pipeline',
        filename: 'broken.pdf',
        contentType: 'application/pdf',
        failureReason: 'The file is damaged.',
        failedAt: '2026-11-13T10:00:00Z',
        uploadedBy: 'Max',
      },
    ]);
    http.expectOne('/api/admin/dead-letters?status=PENDING').flush(deadLetters);
    http.expectOne('/api/admin/users').flush(users);
  }

  async function open() {
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();
    flushLoad();
    await settle();
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('shows pipeline health, usage of all workspaces, failures and dead letters', async () => {
    const { el } = await open();
    expect(el.querySelector('[data-testid="admin-overview"]')?.textContent).toContain('FAILED');
    expect(el.querySelector('[data-testid="usage-by-workspace"]')?.textContent).toContain(
      'Deleted workspace',
    );
    expect(el.querySelector('[data-testid="failures"]')?.textContent).toContain(
      'The file is damaged.',
    );
    const dl = el.querySelector('[data-testid="dead-letters"]')!;
    expect(dl.textContent).toContain('broken.pdf');
    // The list shows the root cause only; the framework wrapper lives in the Details expander.
    expect(dl.querySelector('[data-testid="dead-letter-error"]')?.textContent?.trim()).toBe(
      'Connection refused',
    );
    expect(
      dl.querySelector('details [data-testid="dead-letter-full-message"]')?.textContent,
    ).toContain('Listener method');
    expect(dl.querySelector('[data-testid="retry-hint"]')).toBeNull();
    expect(dl.textContent).toContain('document.uploaded-1@42');
  });

  it('explains why Retry is disabled for events of deleted documents and unreadable events', async () => {
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();
    flushLoad([
      { ...deadLetter, id: 'gone', documentName: null, documentStatus: null, retryable: false },
      {
        ...deadLetter,
        id: 'junk',
        documentId: null,
        documentName: null,
        documentStatus: null,
        retryable: false,
      },
    ]);
    await settle();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;

    const items = el.querySelectorAll('[data-testid="dead-letters"] > li');
    expect(items[0].textContent).toContain('Deleted document');
    expect(items[0].querySelector('[data-testid="retry-hint"]')?.textContent).toContain(
      'Document was deleted — dismiss this event',
    );
    const retry = Array.from(items[0].querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Retry'),
    )!;
    expect(retry.disabled).toBe(true);
    expect(retry.getAttribute('aria-describedby')).toBe('retry-hint-gone');
    expect(items[1].querySelector('[data-testid="retry-hint"]')?.textContent).toContain(
      'could not be read',
    );
  });

  it('retries a dead letter and reports the result', async () => {
    const { fixture, el } = await open();
    const retry = Array.from(
      el.querySelectorAll<HTMLButtonElement>('[data-testid="dead-letters"] button'),
    ).find((b) => b.textContent?.includes('Retry'))!;
    retry.click();

    const request: TestRequest = http.expectOne({
      method: 'POST',
      url: '/api/admin/dead-letters/dl1/retry',
    });
    request.flush({ ...deadLetter, status: 'RETRIED' });
    await settle();
    flushLoad([]);
    await settle();
    await fixture.whenStable();

    expect(el.querySelector('[data-testid="admin-notice"]')?.textContent).toContain(
      '"broken.pdf" was queued again.',
    );
    expect(el.querySelector('[data-testid="dead-letters"]')?.textContent).toContain(
      'No pending dead-letter events',
    );
  });

  it("shows the API's reason when a retry is refused", async () => {
    const { fixture, el } = await open();
    Array.from(
      el.querySelectorAll<HTMLButtonElement>('[data-testid="failures"] button'),
    )[0].click();

    http
      .expectOne({ method: 'POST', url: '/api/admin/documents/d1/reindex' })
      .flush(
        { detail: 'The document is still being processed.' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle();
    flushLoad();
    await settle();
    await fixture.whenStable();

    expect(el.querySelector('[data-testid="admin-notice"]')?.textContent).toContain(
      'still being processed',
    );
  });

  it('overrides and restores a workspace limit', async () => {
    const { fixture, el } = await open();
    const input = el.querySelector<HTMLInputElement>('#limit-u9')!;
    input.value = '10';
    Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid="admin-users"] button'))
      .find((b) => b.textContent?.includes('Save'))!
      .click();

    const request = http.expectOne({ method: 'PUT', url: '/api/admin/users/u9/workspace-limit' });
    expect(request.request.body).toEqual({ limit: 10 });
    request.flush({ ...user, workspaceLimitOverride: 10, workspaceLimit: 10 });
    await settle();
    flushLoad([deadLetter], [{ ...user, workspaceLimitOverride: 10, workspaceLimit: 10 }]);
    await settle();
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="admin-notice"]')?.textContent).toContain(
      'max may create 10 workspaces',
    );

    Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid="admin-users"] button'))
      .find((b) => b.textContent?.includes('Use default'))!
      .click();
    const restore = http.expectOne({ method: 'PUT', url: '/api/admin/users/u9/workspace-limit' });
    expect(restore.request.body).toEqual({ limit: null });
    restore.flush(user);
    await settle();
    flushLoad();
    await settle();
  });

  describe('lowering a limit below the workspaces a user already has', () => {
    const saveButton = (el: HTMLElement) =>
      Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid="admin-users"] button')).find(
        (b) => b.textContent?.includes('Save'),
      )!;

    it('asks first, explaining that existing workspaces are kept, and does nothing when cancelled', async () => {
      const { fixture, el } = await open();
      el.querySelector<HTMLInputElement>('#limit-u9')!.value = '2';
      saveButton(el).click();
      await settle();

      expect(confirmDialog.title()).toBe("Lower Max Member's workspace limit to 2?");
      expect(confirmDialog.message()).toContain('already has 3 workspaces');
      expect(confirmDialog.message()).toContain('They keep all of them');
      expect(confirmDialog.message()).toContain('only creating new workspaces is blocked');
      expect(confirmDialog.variant()).toBe('default');

      confirmDialog.cancel().click();
      await settle();
      await fixture.whenStable();
      expect(confirmDialog.element()).toBeNull();
      http.expectNone({ method: 'PUT', url: '/api/admin/users/u9/workspace-limit' });
    });

    it('saves once confirmed', async () => {
      const { fixture, el } = await open();
      el.querySelector<HTMLInputElement>('#limit-u9')!.value = '2';
      saveButton(el).click();
      await settle();

      confirmDialog.accept().click();
      const request = http.expectOne({ method: 'PUT', url: '/api/admin/users/u9/workspace-limit' });
      expect(request.request.body).toEqual({ limit: 2 });
      request.flush({ ...user, workspaceLimitOverride: 2, workspaceLimit: 2 });
      await settle();
      flushLoad([deadLetter], [{ ...user, workspaceLimitOverride: 2, workspaceLimit: 2 }]);
      await settle();
      await fixture.whenStable();

      expect(confirmDialog.element()).toBeNull();
      expect(el.querySelector('[data-testid="admin-notice"]')?.textContent).toContain(
        'max may now create at most 2 workspaces',
      );
    });

    it('also asks when "Use default" would put the user over the default limit', async () => {
      const over = {
        ...user,
        workspacesCreated: 5,
        workspaceLimitOverride: 10,
        workspaceLimit: 10,
      };
      const fixture = TestBed.createComponent(AdminPage);
      fixture.detectChanges();
      flushLoad([deadLetter], [over]);
      await settle();
      await fixture.whenStable();
      const el = fixture.nativeElement as HTMLElement;

      Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid="admin-users"] button'))
        .find((b) => b.textContent?.includes('Use default'))!
        .click();
      await settle();

      expect(confirmDialog.title()).toBe("Lower Max Member's workspace limit to 3?");
      confirmDialog.cancel().click();
      await settle();
    });

    it('does not ask when the new limit is at or above what the user has, or unlimited', async () => {
      const { el } = await open();
      el.querySelector<HTMLInputElement>('#limit-u9')!.value = '0';
      saveButton(el).click();
      await settle();

      expect(confirmDialog.element()).toBeNull();
      http.expectOne({ method: 'PUT', url: '/api/admin/users/u9/workspace-limit' }).flush(user);
      await settle();
      flushLoad();
      await settle();
    });
  });

  it('shows an error with "Try again" instead of an empty list when loading fails', async () => {
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();
    const failed = { status: 500, statusText: 'Server Error' };
    http.expectOne('/api/admin/overview').flush({ detail: 'Database unavailable.' }, failed);
    http.expectOne('/api/admin/usage?days=30').flush(REPORT);
    http.expectOne('/api/admin/failures').flush({ detail: 'Database unavailable.' }, failed);
    http
      .expectOne('/api/admin/dead-letters?status=PENDING')
      .flush({ detail: 'Database unavailable.' }, failed);
    http.expectOne('/api/admin/users').flush({ detail: 'Database unavailable.' }, failed);
    await settle();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;

    const errors = Array.from(el.querySelectorAll('[data-testid="load-error"]')).map(
      (e) => e.textContent,
    );
    expect(errors).toHaveLength(4);
    expect(errors[0]).toContain("Couldn't load pipeline status.");
    expect(errors.join()).toContain('Database unavailable.');
    expect(el.textContent).not.toContain('No failed documents');
    expect(el.textContent).not.toContain('No pending dead-letter events');
    expect(el.textContent).not.toContain('No users found');

    // "Try again" on the failed documents section reloads just that section.
    el.querySelectorAll<HTMLButtonElement>('[data-testid="load-error-retry"]')[1].click();
    http.expectOne('/api/admin/failures').flush([]);
    await settle();
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="failures"]')?.textContent).toContain(
      'No failed documents',
    );
    expect(el.querySelectorAll('[data-testid="load-error"]')).toHaveLength(3);
  });

  it('rejects a negative limit without calling the API', async () => {
    const { fixture, el } = await open();
    el.querySelector<HTMLInputElement>('#limit-u9')!.value = '-2';
    Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid="admin-users"] button'))
      .find((b) => b.textContent?.includes('Save'))!
      .click();
    await fixture.whenStable();

    expect(el.querySelector('[data-testid="admin-notice"]')?.textContent).toContain('0 or more');
  });
});

describe('adminGuard', () => {
  function run(admin: boolean) {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const result = TestBed.runInInjectionContext(() =>
      adminGuard({} as never, {} as never),
    ) as Promise<boolean | UrlTree>;
    http
      .expectOne('/api/me')
      .flush({ id: 'u', username: 'u', email: null, displayName: 'U', admin, workspaces: [] });
    return result;
  }

  it('lets admins in', async () => {
    expect(await run(true)).toBe(true);
    expect(TestBed.inject(WorkspaceContext).isAdmin()).toBe(true);
  });

  it('sends everyone else home', async () => {
    const result = await run(false);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/');
  });
});
