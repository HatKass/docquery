import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { Subscription, firstValueFrom } from 'rxjs';
import { fileExtension, formatBytes } from '../shared/format';
import { ConfirmDialog } from '../shared/confirm-dialog';
import { problemMessage } from '../shared/problem';
import { WorkspaceContext } from '../workspaces/workspace-context.service';
import { DocumentApi } from './document-api.service';
import { DocumentEvents, StreamRejectedError } from './document-events.service';
import { DocumentOpener } from './document-opener.service';
import { DocumentStatus, DocumentSummary, IN_PROGRESS_STATUSES, UploadPolicy } from './document.model';

type StreamState = 'connecting' | 'live' | 'reconnecting' | 'offline';

/** How often the page re-checks whether a queued document counts as stuck. */
const STUCK_CHECK_INTERVAL_MS = 30_000;

/** A file the user picked that is being uploaded or was rejected. */
interface PendingUpload {
  key: number;
  name: string;
  state: 'uploading' | 'failed';
  error?: string;
}

const STATUS_LABELS: Record<DocumentStatus, string> = {
  UPLOADED: 'Queued',
  EXTRACTING: 'Extracting text',
  CHUNKING: 'Chunking',
  EMBEDDING: 'Embedding',
  READY: 'Ready',
  FAILED: 'Failed',
};

/**
 * The workspace's document library: upload (members and owners), list with live processing status,
 * open and delete. Status changes arrive over SSE; after every (re)connect the list is re-fetched,
 * so nothing is missed while the connection was down.
 */
@Component({
  selector: 'app-document-library-page',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (notFound()) {
      <section class="mx-auto max-w-md rounded-xl border border-slate-200 bg-white p-6 text-sm" data-testid="workspace-not-found">
        <h2 class="font-semibold text-slate-900">Workspace not found</h2>
        <p class="mt-2 text-slate-600">It does not exist or you are not a member.</p>
      </section>
    } @else if (workspace(); as ws) {
      <section class="mx-auto max-w-5xl space-y-6">
        <div>
          <div class="flex items-end justify-between gap-4">
            <h2 class="min-w-0 truncate text-xl font-semibold text-slate-900" data-testid="workspace-name">{{ ws.name }}</h2>
            <span class="flex shrink-0 items-center gap-2 text-xs text-slate-500" data-testid="stream-state">
              <span class="h-2 w-2 rounded-full" [class]="streamDotClass()"></span>
              {{ streamLabel() }}
            </span>
          </div>
        </div>

        @if (canUpload()) {
          <div
            class="rounded-xl border-2 border-dashed bg-white p-4 text-center transition-colors sm:p-6"
            [class]="dragging() ? 'border-indigo-500 bg-indigo-50' : 'border-slate-300'"
            data-testid="upload-zone"
            (dragover)="onDragOver($event)"
            (dragleave)="dragging.set(false)"
            (drop)="onDrop($event)"
          >
            <p class="text-sm text-slate-700">
              <span class="hidden sm:inline">Drag files here, or </span>
              <label for="file-input" class="cursor-pointer font-medium text-indigo-700 hover:underline"
                ><span class="sm:hidden">Choose files to upload</span><span class="hidden sm:inline">browse</span></label
              >
            </p>
            <input
              id="file-input"
              type="file"
              multiple
              class="sr-only"
              data-testid="file-input"
              [accept]="acceptAttribute()"
              (change)="onFilesPicked($any($event.target))"
            />
            <p class="mt-1 text-xs text-slate-500">{{ policyHint() }}</p>
          </div>
        } @else {
          <p class="rounded-md bg-slate-100 p-3 text-sm text-slate-600" data-testid="read-only-hint">
            You can read documents in this workspace. Ask an owner for the MEMBER role to upload.
          </p>
        }

        @if (pending().length) {
          <ul class="space-y-2" data-testid="pending-uploads">
            @for (p of pending(); track p.key) {
              <li
                class="flex items-center justify-between gap-3 rounded-md border px-3 py-2 text-sm"
                [class]="p.state === 'failed' ? 'border-red-200 bg-red-50' : 'border-slate-200 bg-white'"
              >
                <span class="truncate">
                  <span class="font-medium text-slate-800">{{ p.name }}</span>
                  @if (p.state === 'uploading') {
                    <span class="ml-2 text-slate-500">Uploading…</span>
                  } @else {
                    <span class="ml-2 text-red-800" role="alert">{{ p.error }}</span>
                  }
                </span>
                @if (p.state === 'failed') {
                  <button type="button" class="text-xs text-slate-600 hover:underline" (click)="dismiss(p.key)">Dismiss</button>
                }
              </li>
            }
          </ul>
        }

        <div class="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
          @if (loading() && !documents().length) {
            <p class="p-6 text-sm text-slate-500">Loading documents…</p>
          } @else if (!documents().length) {
            <p class="p-6 text-sm text-slate-600" data-testid="no-documents">
              No documents yet.{{ canUpload() ? ' Upload a PDF, Word, text or Markdown file to get started.' : '' }}
            </p>
          } @else {
            <table class="w-full text-left text-sm" data-testid="document-table">
              <thead class="border-b border-slate-200 text-xs uppercase text-slate-500">
                <!-- Phones show Name + Actions (status and details fold into the name cell); tablets add
                     Status; desktops (lg) show every column. -->
                <tr>
                  <th class="px-3 py-2 sm:px-4">Name</th>
                  <th class="hidden px-2 py-2 sm:table-cell">Status</th>
                  <th class="hidden px-2 py-2 text-right lg:table-cell">Pages</th>
                  <th class="hidden px-2 py-2 text-right lg:table-cell">Chunks</th>
                  <th class="hidden px-2 py-2 text-right lg:table-cell">Size</th>
                  <th class="hidden px-2 py-2 lg:table-cell">Uploaded</th>
                  <th class="px-3 py-2 sm:px-4"><span class="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (d of documents(); track d.id) {
                  <tr [attr.data-document-id]="d.id">
                    <td class="w-full max-w-0 px-3 py-2 sm:px-4 lg:w-auto lg:max-w-xs">
                      <button
                        type="button"
                        class="block max-w-full truncate text-left font-medium text-indigo-700 hover:underline"
                        [title]="d.filename"
                        (click)="open(d)"
                      >
                        {{ d.filename }}
                      </button>
                      <!-- Phones and tablets: the hidden columns, in one line under the name. -->
                      <div class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500 lg:hidden">
                        <span class="sm:hidden">
                          <span
                            class="inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 font-medium"
                            [class]="statusClass(d.status)"
                            data-testid="status-compact"
                          >
                            @if (inProgress(d.status)) {
                              <span class="h-1.5 w-1.5 animate-pulse rounded-full bg-current"></span>
                            }
                            {{ statusLabel(d.status) }}
                          </span>
                        </span>
                        <span class="whitespace-nowrap">{{ size(d.sizeBytes) }}</span>
                        @if (d.pageCount !== null) {
                          <span class="whitespace-nowrap">· {{ d.pageCount }} {{ d.pageCount === 1 ? 'page' : 'pages' }}</span>
                        }
                        @if (d.chunkCount !== null) {
                          <span class="whitespace-nowrap">· {{ d.chunkCount }} {{ d.chunkCount === 1 ? 'chunk' : 'chunks' }}</span>
                        }
                        <span class="whitespace-nowrap">· {{ d.uploadedAt | date: 'MMM d, HH:mm' }}</span>
                      </div>
                      @if (d.status === 'FAILED' && d.failureReason) {
                        <p class="mt-0.5 text-xs text-red-700" data-testid="failure-reason">{{ d.failureReason }}</p>
                      }
                    </td>
                    <td class="hidden px-2 py-2 sm:table-cell">
                      <span
                        class="inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium"
                        [class]="statusClass(d.status)"
                        data-testid="status"
                      >
                        @if (inProgress(d.status)) {
                          <span class="h-1.5 w-1.5 animate-pulse rounded-full bg-current"></span>
                        }
                        {{ statusLabel(d.status) }}
                      </span>
                    </td>
                    <td class="hidden px-2 py-2 text-right tabular-nums text-slate-700 lg:table-cell">{{ d.pageCount ?? '—' }}</td>
                    <td class="hidden px-2 py-2 text-right tabular-nums text-slate-700 lg:table-cell">{{ d.chunkCount ?? '—' }}</td>
                    <td class="hidden whitespace-nowrap px-2 py-2 text-right tabular-nums text-slate-700 lg:table-cell">{{ size(d.sizeBytes) }}</td>
                    <td class="hidden whitespace-nowrap px-2 py-2 text-slate-600 lg:table-cell">
                      {{ d.uploadedAt | date: 'MMM d, y, HH:mm' }}
                      <span class="block text-xs text-slate-500">{{ d.uploadedByName ?? 'Former member' }}</span>
                    </td>
                    <td class="px-3 py-2 text-right sm:px-4">
                      @if (canUpload()) {
                        <!-- Stacked on phones (bigger tap targets), side by side from sm up. -->
                        <div class="flex flex-col items-end gap-2 sm:flex-row sm:justify-end sm:gap-3">
                        @if (d.status === 'READY' || d.status === 'FAILED' || isStuck(d)) {
                          <button
                            type="button"
                            class="whitespace-nowrap text-slate-700 hover:underline"
                            data-testid="reindex"
                            [title]="reindexTitle(d)"
                            (click)="reindex(d)"
                          >
                            {{ d.status === 'READY' ? 'Re-index' : 'Retry' }}
                          </button>
                        }
                        <button
                          type="button"
                          class="text-red-700 hover:underline"
                          data-testid="delete-document"
                          [attr.aria-label]="'Delete ' + d.filename"
                          (click)="remove(d)"
                        >
                          Delete
                        </button>
                        </div>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>

        @if (error()) {
          <p class="rounded-md bg-red-50 p-3 text-sm text-red-800" role="alert" data-testid="library-error">{{ error() }}</p>
        }
      </section>
    } @else {
      <p class="text-center text-sm text-slate-500">Loading…</p>
    }
  `,
})
export class DocumentLibraryPage {
  /** Bound from the `:workspaceId` route parameter. */
  readonly workspaceId = input.required<string>();

  private readonly api = inject(DocumentApi);
  private readonly events = inject(DocumentEvents);
  private readonly context = inject(WorkspaceContext);
  private readonly confirm = inject(ConfirmDialog);
  private readonly opener = inject(DocumentOpener);

  protected readonly documents = signal<DocumentSummary[]>([]);
  protected readonly policy = signal<UploadPolicy | null>(null);
  protected readonly pending = signal<PendingUpload[]>([]);
  protected readonly loading = signal(false);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly streamState = signal<StreamState>('connecting');
  protected readonly dragging = signal(false);

  protected readonly workspace = computed(() => this.context.workspaces().find((w) => w.id === this.workspaceId()) ?? null);
  protected readonly canUpload = computed(() => {
    const role = this.workspace()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });
  protected readonly acceptAttribute = computed(() =>
    (this.policy()?.allowedExtensions ?? ['pdf', 'docx', 'txt', 'md']).map((e) => '.' + e).join(','),
  );
  protected readonly policyHint = computed(() => {
    const policy = this.policy();
    return policy ? `PDF, DOCX, TXT or Markdown · up to ${formatBytes(policy.maxFileSizeBytes)} each` : 'PDF, DOCX, TXT or Markdown';
  });
  protected readonly streamLabel = computed(
    () => ({ connecting: 'Connecting…', live: 'Live', reconnecting: 'Reconnecting…', offline: 'Live updates off' })[this.streamState()],
  );
  protected readonly streamDotClass = computed(
    () =>
      ({ connecting: 'bg-slate-300', live: 'bg-emerald-500', reconnecting: 'bg-amber-400', offline: 'bg-slate-400' })[
        this.streamState()
      ],
  );

  /** Current time, refreshed periodically so "stuck in the queue" appears without a reload. */
  protected readonly now = signal(Date.now());

  private stream: Subscription | null = null;
  private uploadKey = 0;

  constructor() {
    // Re-open whenever the route parameter changes (e.g. via the workspace switcher).
    effect(() => {
      const id = this.workspaceId();
      untracked(() => void this.show(id));
    });
    const clock = setInterval(() => this.now.set(Date.now()), STUCK_CHECK_INTERVAL_MS);
    inject(DestroyRef).onDestroy(() => {
      this.stream?.unsubscribe();
      clearInterval(clock);
    });
  }

  private async show(id: string): Promise<void> {
    this.stream?.unsubscribe();
    this.notFound.set(false);
    this.error.set(null);
    this.documents.set([]);
    this.pending.set([]);
    try {
      if (!this.context.loaded()) {
        await this.context.load();
      }
      if (!this.context.workspaces().some((w) => w.id === id)) {
        this.notFound.set(true);
        return;
      }
      this.context.select(id);
      if (!this.policy()) {
        firstValueFrom(this.api.uploadPolicy()).then(
          (p) => this.policy.set(p),
          () => undefined, // hints only; the API enforces the limits anyway
        );
      }
      this.connect(id);
      await this.reload();
    } catch (e) {
      this.error.set(problemMessage(e));
    }
  }

  private connect(id: string): void {
    this.streamState.set('connecting');
    this.stream = this.events.connect(id).subscribe({
      next: (message) => {
        switch (message.type) {
          case 'ready':
            this.streamState.set('live');
            // (Re)connected: fetch the full list, in case events were missed while disconnected.
            void this.reload().catch(() => undefined);
            break;
          case 'reconnecting':
            this.streamState.set('reconnecting');
            break;
          case 'created':
          case 'updated':
            this.upsert(message.document);
            break;
          case 'deleted':
            this.documents.update((docs) => docs.filter((d) => d.id !== message.documentId));
            break;
        }
      },
      error: (e: unknown) => {
        this.streamState.set('offline');
        if (e instanceof StreamRejectedError && e.status === 404) {
          // Removed from the workspace (or it was deleted) while looking at it.
          void this.context.load().then(() => this.notFound.set(!this.workspace()));
        }
      },
    });
  }

  private async reload(): Promise<void> {
    const id = this.workspaceId();
    this.loading.set(true);
    try {
      const docs = await firstValueFrom(this.api.list(id));
      if (id === this.workspaceId()) {
        this.documents.set(docs);
      }
    } finally {
      this.loading.set(false);
    }
  }

  private upsert(doc: DocumentSummary): void {
    if (doc.workspaceId !== this.workspaceId()) {
      return;
    }
    this.documents.update((docs) => {
      const others = docs.filter((d) => d.id !== doc.id);
      return [doc, ...others].sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt) || a.id.localeCompare(b.id));
    });
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    void this.uploadAll(Array.from(event.dataTransfer?.files ?? []));
  }

  protected onFilesPicked(input: HTMLInputElement): void {
    const files = Array.from(input.files ?? []);
    input.value = ''; // picking the same file again should trigger another change event
    void this.uploadAll(files);
  }

  /** Uploads one after another: predictable order and no burst against the upload limits. */
  async uploadAll(files: File[]): Promise<void> {
    for (const file of files) {
      await this.uploadOne(file);
    }
  }

  private async uploadOne(file: File): Promise<void> {
    const key = ++this.uploadKey;
    const rejection = this.precheck(file);
    if (rejection) {
      this.pending.update((list) => [...list, { key, name: file.name, state: 'failed', error: rejection }]);
      return;
    }
    this.pending.update((list) => [...list, { key, name: file.name, state: 'uploading' }]);
    try {
      const created = await firstValueFrom(this.api.upload(this.workspaceId(), file));
      this.upsert(created);
      this.dismiss(key);
    } catch (e) {
      this.pending.update((list) =>
        list.map((p) => (p.key === key ? { ...p, state: 'failed' as const, error: problemMessage(e) } : p)),
      );
    }
  }

  /** Instant feedback for obvious mistakes; the API validates the real content anyway. */
  private precheck(file: File): string | null {
    const allowed = this.policy()?.allowedExtensions ?? ['pdf', 'docx', 'txt', 'md', 'markdown'];
    if (!allowed.includes(fileExtension(file.name))) {
      return 'Unsupported file type. Upload PDF, Word (.docx), text or Markdown files.';
    }
    if (file.size === 0) {
      return 'The file is empty.';
    }
    const max = this.policy()?.maxFileSizeBytes;
    if (max && file.size > max) {
      return `Too large (${formatBytes(file.size)}); the limit is ${formatBytes(max)}.`;
    }
    return null;
  }

  protected dismiss(key: number): void {
    this.pending.update((list) => list.filter((p) => p.key !== key));
  }

  protected async remove(doc: DocumentSummary): Promise<void> {
    this.error.set(null);
    const deleted = await this.confirm.open({
      title: `Delete ${doc.filename}?`,
      message:
        'The file, its text chunks and embeddings will be permanently removed for everyone in this workspace. ' +
        'This cannot be undone.',
      variant: 'danger',
      confirmLabel: 'Delete document',
      busyLabel: 'Deleting…',
      action: () => firstValueFrom(this.api.delete(this.workspaceId(), doc.id)),
    });
    if (deleted) {
      this.documents.update((docs) => docs.filter((d) => d.id !== doc.id));
    }
  }

  /** Queues the document for processing again; its progress then arrives over the live stream. */
  protected async reindex(doc: DocumentSummary): Promise<void> {
    this.error.set(null);
    try {
      this.upsert(await firstValueFrom(this.api.reindex(this.workspaceId(), doc.id)));
    } catch (e) {
      this.error.set(problemMessage(e));
    }
  }

  /** Opens PDFs and text in a new tab (the browser's viewer); Word files download. */
  protected async open(doc: DocumentSummary): Promise<void> {
    this.error.set(null);
    try {
      await this.opener.open(this.workspaceId(), doc);
    } catch (e) {
      this.error.set(problemMessage(e));
    }
  }

  /**
   * Still "Queued" long after it entered the queue: its ingestion request was probably lost (or the
   * document predates the pipeline). The API enforces the same threshold.
   */
  protected isStuck(doc: DocumentSummary): boolean {
    const after = this.policy()?.requeueAfterSeconds;
    return doc.status === 'UPLOADED' && !!after && this.now() - Date.parse(doc.statusChangedAt) > after * 1000;
  }

  protected reindexTitle(doc: DocumentSummary): string {
    if (doc.status === 'READY') {
      return 'Rebuild the search index';
    }
    if (doc.status === 'FAILED') {
      return 'Try processing this document again';
    }
    return 'Still queued after a long time: queue it again';
  }

  protected statusLabel(status: DocumentStatus): string {
    return STATUS_LABELS[status];
  }

  protected inProgress(status: DocumentStatus): boolean {
    return IN_PROGRESS_STATUSES.includes(status);
  }

  protected statusClass(status: DocumentStatus): string {
    switch (status) {
      case 'READY':
        return 'bg-emerald-50 text-emerald-800';
      case 'FAILED':
        return 'bg-red-50 text-red-800';
      default:
        return 'bg-amber-50 text-amber-800';
    }
  }

  protected size(bytes: number): string {
    return formatBytes(bytes);
  }
}
