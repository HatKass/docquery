import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { Subscription, firstValueFrom } from 'rxjs';
import { DocumentApi } from '../documents/document-api.service';
import { DocumentOpener } from '../documents/document-opener.service';
import { ConfirmDialog } from '../shared/confirm-dialog';
import { problemMessage } from '../shared/problem';
import { WorkspaceContext } from '../workspaces/workspace-context.service';
import { WorkspaceNav } from '../workspaces/workspace-nav';
import { AnswerContent } from './answer-content';
import { citationLabel } from './answer-segments';
import { AnswerStream, AskRejectedError } from './answer-stream.service';
import { ChatApi } from './chat-api.service';
import { ChatMessage, Citation, ConversationSummary, NOT_FOUND_ANSWER } from './chat.model';

/** The answer being streamed right now. */
interface PendingAnswer {
  id: string | null;
  text: string;
}

/**
 * Ask questions about the workspace's documents. Answers stream in as they are generated; their
 * `[n]` markers become chips that open the citation viewer (document, page, quoted snippet, and a
 * button that opens the document at that page). Conversations are private to the signed-in user.
 */
@Component({
  selector: 'app-chat-page',
  imports: [DatePipe, WorkspaceNav, AnswerContent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (notFound()) {
      <section class="mx-auto max-w-md rounded-xl border border-slate-200 bg-white p-6 text-sm" data-testid="workspace-not-found">
        <h2 class="font-semibold text-slate-900">Workspace not found</h2>
        <p class="mt-2 text-slate-600">It does not exist or you are not a member.</p>
      </section>
    } @else if (workspace(); as ws) {
      <section class="mx-auto max-w-6xl space-y-6">
        <div>
          <h2 class="truncate text-xl font-semibold text-slate-900" data-testid="workspace-name">{{ ws.name }}</h2>
          <div class="mt-4"><app-workspace-nav [workspaceId]="ws.id" /></div>
        </div>

        <!-- Desktop: conversations | messages | citation viewer. Phones and tablets: one column, the
             conversation list collapses behind a toggle and the viewer opens as a bottom sheet. -->
        <div class="grid grid-cols-1 gap-4 lg:grid-cols-[14rem_minmax(0,1fr)_20rem]">
          <!-- Conversations -->
          <aside class="min-w-0 space-y-2" aria-label="Conversations">
            <div class="flex gap-2">
              <button
                type="button"
                class="flex min-w-0 flex-1 items-center justify-between gap-2 rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-700 lg:hidden"
                data-testid="toggle-conversations"
                aria-controls="conversation-list"
                [attr.aria-expanded]="showConversations()"
                (click)="showConversations.set(!showConversations())"
              >
                <span class="truncate">{{ activeTitle() }}</span>
                <span class="shrink-0 text-xs text-slate-500">{{ conversations().length }} {{ showConversations() ? '▴' : '▾' }}</span>
              </button>
              <button
                type="button"
                class="shrink-0 rounded-md bg-indigo-600 px-3 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50 lg:w-full"
                data-testid="new-conversation"
                [disabled]="streaming()"
                (click)="newConversation()"
              >
                <span class="lg:hidden">New</span><span class="hidden lg:inline">New conversation</span>
              </button>
            </div>
            <ul
              id="conversation-list"
              class="space-y-1 rounded-md border border-slate-200 bg-white p-1 text-sm lg:block lg:border-0 lg:bg-transparent lg:p-0"
              [class.hidden]="!showConversations()"
              data-testid="conversation-list"
            >
              @for (c of conversations(); track c.id) {
                <li class="group flex items-center gap-1">
                  <button
                    type="button"
                    class="min-w-0 flex-1 truncate rounded-md px-2 py-1.5 text-left hover:bg-slate-100"
                    [class]="c.id === activeId() ? 'bg-slate-200 font-medium text-slate-900' : 'text-slate-700'"
                    [title]="c.title"
                    [attr.aria-current]="c.id === activeId() ? 'true' : null"
                    [disabled]="streaming()"
                    (click)="select(c.id)"
                  >
                    {{ c.title }}
                  </button>
                  <button
                    type="button"
                    class="rounded px-2 py-1.5 text-xs text-slate-400 hover:text-red-700 lg:px-1 lg:py-0"
                    [attr.aria-label]="'Delete conversation ' + c.title"
                    data-testid="delete-conversation"
                    [disabled]="streaming()"
                    (click)="remove(c)"
                  >
                    ✕
                  </button>
                </li>
              } @empty {
                <li class="px-2 text-xs text-slate-500">No conversations yet.</li>
              }
            </ul>
          </aside>

          <!-- Messages and composer -->
          <div class="flex min-h-[22rem] min-w-0 flex-col rounded-xl border border-slate-200 bg-white shadow-sm sm:min-h-[28rem]">
            <div #scroller class="max-h-[60dvh] flex-1 space-y-4 overflow-y-auto p-3 sm:p-4" data-testid="messages" aria-live="polite">
              @if (!messages().length && !pending()) {
                <div class="py-10 text-center text-sm text-slate-600" data-testid="chat-empty">
                  <p class="font-medium text-slate-800">Ask a question about this workspace's documents.</p>
                  <p class="mt-1">Answers cite the document and page they come from.</p>
                  @if (readyCount() === 0) {
                    <p class="mt-3 rounded-md bg-amber-50 p-2 text-amber-800" data-testid="no-ready-documents">
                      No document is ready yet. Upload documents (or wait for processing) on the Documents tab.
                    </p>
                  }
                </div>
              }
              @for (m of messages(); track m.id) {
                @if (m.role === 'USER') {
                  <div class="flex justify-end">
                    <p class="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-indigo-600 px-4 py-2 text-sm text-white" data-testid="question">{{ m.content }}</p>
                  </div>
                } @else {
                  <div class="max-w-[90%]" data-testid="answer" [attr.data-status]="m.status">
                    <div
                      class="break-words rounded-2xl rounded-bl-sm px-4 py-2 text-sm leading-relaxed"
                      [class]="answerClass(m)"
                    >
                      <app-answer-content
                        [content]="m.content"
                        [citations]="m.citations"
                        [selected]="selected()"
                        (citationSelected)="selected.set($event)"
                      />
                    </div>
                    @if (m.citations.length) {
                      <ul class="mt-1 flex flex-wrap gap-x-3 gap-y-1 px-1 text-xs text-slate-500" data-testid="sources">
                        @for (c of m.citations; track c.sourceNumber) {
                          <li>
                            <button type="button" class="hover:text-indigo-700 hover:underline" (click)="selected.set(c)">
                              [{{ c.sourceNumber }}] {{ label(c) }}
                            </button>
                          </li>
                        }
                      </ul>
                    }
                    <p class="mt-0.5 px-1 text-[11px] text-slate-400">{{ m.createdAt | date: 'HH:mm' }}</p>
                  </div>
                }
              }
              @if (pending(); as p) {
                <div class="max-w-[90%]" data-testid="pending-answer">
                  <div class="break-words rounded-2xl rounded-bl-sm bg-slate-100 px-4 py-2 text-sm leading-relaxed text-slate-800">
                    @if (p.text) {
                      <!-- Markers stay plain text until the stored answer (with its citations) arrives. -->
                      <app-answer-content [content]="p.text" />
                      <span class="mt-1 inline-block h-3 w-1.5 animate-pulse bg-slate-500 align-middle" aria-hidden="true"></span>
                    } @else {
                      <span class="text-slate-500">Searching your documents…</span>
                    }
                  </div>
                </div>
              }
            </div>

            @if (error()) {
              <p class="mx-4 mb-2 rounded-md bg-red-50 p-2 text-sm text-red-800" role="alert" data-testid="chat-error">{{ error() }}</p>
            }

            <form class="flex items-end gap-2 border-t border-slate-200 p-3" (submit)="$event.preventDefault(); send()">
              <label for="question" class="sr-only">Your question</label>
              <textarea
                id="question"
                rows="2"
                class="min-h-[2.75rem] flex-1 resize-y rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-indigo-500 focus:outline-none"
                placeholder="Ask about your documents…"
                title="Enter sends, Shift+Enter starts a new line"
                maxlength="2000"
                data-testid="question-input"
                [value]="draft()"
                [disabled]="streaming()"
                (input)="draft.set($any($event.target).value)"
                (keydown.enter)="onEnter($any($event))"
              ></textarea>
              @if (streaming()) {
                <button
                  type="button"
                  class="rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50"
                  data-testid="stop"
                  (click)="stop()"
                >
                  Stop
                </button>
              } @else {
                <button
                  type="submit"
                  class="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
                  data-testid="send"
                  [disabled]="!draft().trim()"
                >
                  Ask
                </button>
              }
            </form>
          </div>

          <!-- Citation viewer: a column on desktop, a bottom sheet over a backdrop on smaller screens. -->
          @if (selected()) {
            <div class="fixed inset-0 z-30 bg-slate-900/40 lg:hidden" data-testid="citation-backdrop" (click)="selected.set(null)"></div>
          }
          <aside
            class="fixed inset-x-0 bottom-0 z-40 max-h-[75dvh] min-w-0 overflow-y-auto rounded-t-2xl border border-slate-200 bg-white p-4 pb-6 text-sm shadow-2xl lg:static lg:z-auto lg:block lg:max-h-none lg:overflow-visible lg:rounded-xl lg:pb-4 lg:shadow-sm"
            [class.hidden]="!selected()"
            aria-label="Source"
            data-testid="citation-viewer"
            (keydown.escape)="selected.set(null)"
          >
            @if (selected(); as c) {
              <div class="flex items-start justify-between gap-2">
                <h3 class="font-semibold text-slate-900">Source {{ c.sourceNumber }}</h3>
                <button type="button" class="px-1 py-1 text-xs text-slate-500 hover:underline" data-testid="close-citation" (click)="selected.set(null)">Close</button>
              </div>
              <p class="mt-2 break-words font-medium text-slate-800" data-testid="citation-document">{{ c.documentName }}</p>
              <p class="text-xs text-slate-500" data-testid="citation-page">
                {{ c.pageNumber ? 'Page ' + c.pageNumber : 'No page numbers (text document)' }}
              </p>
              <blockquote class="mt-3 whitespace-pre-wrap break-words lg:max-h-80 lg:overflow-y-auto border-l-4 border-indigo-200 pl-3 text-slate-700" data-testid="citation-snippet">{{ c.snippet }}</blockquote>
              @if (c.documentId) {
                <button
                  type="button"
                  class="mt-4 w-full rounded-md border border-indigo-200 px-3 py-2 text-sm font-medium text-indigo-700 hover:bg-indigo-50"
                  data-testid="open-citation"
                  (click)="openCitation(c)"
                >
                  {{ openLabel(c) }}
                </button>
              } @else {
                <p class="mt-4 text-xs text-slate-500">This document has since been deleted.</p>
              }
            } @else {
              <p class="text-slate-500">Select a numbered source in an answer to see the exact passage it was based on.</p>
            }
          </aside>
        </div>
      </section>
    } @else {
      <p class="text-center text-sm text-slate-500">Loading…</p>
    }
  `,
})
export class ChatPage {
  /** Bound from the `:workspaceId` route parameter. */
  readonly workspaceId = input.required<string>();
  /** Bound from the optional `?conversation=` query parameter (deep link to a conversation). */
  readonly conversation = input<string | undefined>();

  private readonly chat = inject(ChatApi);
  private readonly answers = inject(AnswerStream);
  private readonly documents = inject(DocumentApi);
  private readonly opener = inject(DocumentOpener);
  private readonly context = inject(WorkspaceContext);
  private readonly confirm = inject(ConfirmDialog);
  private readonly router = inject(Router);
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');

  protected readonly conversations = signal<ConversationSummary[]>([]);
  protected readonly activeId = signal<string | null>(null);
  protected readonly messages = signal<ChatMessage[]>([]);
  protected readonly pending = signal<PendingAnswer | null>(null);
  protected readonly selected = signal<Citation | null>(null);
  protected readonly draft = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly notFound = signal(false);
  protected readonly readyCount = signal<number | null>(null);
  /** Phones and tablets only: whether the conversation list is expanded. */
  protected readonly showConversations = signal(false);

  protected readonly workspace = computed(() => this.context.workspaces().find((w) => w.id === this.workspaceId()) ?? null);
  protected readonly streaming = computed(() => this.pending() !== null);
  protected readonly activeTitle = computed(
    () => this.conversations().find((c) => c.id === this.activeId())?.title ?? 'New conversation',
  );

  private stream: Subscription | null = null;

  constructor() {
    effect(() => {
      const id = this.workspaceId();
      untracked(() => void this.show(id));
    });
    effect(() => {
      const requested = this.conversation();
      untracked(() => {
        if (requested && requested !== this.activeId() && this.conversations().length) {
          void this.load(requested);
        }
      });
    });
    inject(DestroyRef).onDestroy(() => this.stream?.unsubscribe());
  }

  private async show(id: string): Promise<void> {
    this.stop();
    this.notFound.set(false);
    this.error.set(null);
    this.messages.set([]);
    this.activeId.set(null);
    this.selected.set(null);
    try {
      if (!this.context.loaded()) {
        await this.context.load();
      }
      if (!this.context.workspaces().some((w) => w.id === id)) {
        this.notFound.set(true);
        return;
      }
      this.context.select(id);
      firstValueFrom(this.documents.list(id)).then(
        (docs) => this.readyCount.set(docs.filter((d) => d.status === 'READY').length),
        () => this.readyCount.set(null), // only a hint
      );
      const list = await firstValueFrom(this.chat.list(id));
      this.conversations.set(list);
      const requested = this.conversation();
      const initial = list.find((c) => c.id === requested) ?? list[0];
      if (initial) {
        await this.load(initial.id);
      }
    } catch (e) {
      this.error.set(problemMessage(e));
    }
  }

  private async load(conversationId: string): Promise<void> {
    this.activeId.set(conversationId);
    this.selected.set(null);
    this.error.set(null);
    try {
      const detail = await firstValueFrom(this.chat.get(this.workspaceId(), conversationId));
      if (this.activeId() === conversationId) {
        this.messages.set(detail.messages);
        this.scrollToEnd();
      }
    } catch (e) {
      this.error.set(problemMessage(e));
    }
  }

  protected select(conversationId: string): void {
    this.showConversations.set(false);
    void this.router.navigate([], { queryParams: { conversation: conversationId }, replaceUrl: true });
    void this.load(conversationId);
  }

  protected newConversation(): void {
    this.showConversations.set(false);
    this.activeId.set(null);
    this.messages.set([]);
    this.selected.set(null);
    this.error.set(null);
    void this.router.navigate([], { queryParams: { conversation: null }, replaceUrl: true });
  }

  protected onEnter(event: KeyboardEvent): void {
    if (!event.shiftKey) {
      event.preventDefault();
      void this.send();
    }
  }

  /** Sends the draft; a conversation is created on the first question. */
  async send(): Promise<void> {
    const question = this.draft().trim();
    if (!question || this.streaming()) {
      return;
    }
    this.error.set(null);
    const workspaceId = this.workspaceId();
    let conversationId = this.activeId();
    this.pending.set({ id: null, text: '' });
    try {
      if (!conversationId) {
        const created = await firstValueFrom(this.chat.create(workspaceId));
        conversationId = created.id;
        this.conversations.update((list) => [created, ...list]);
        this.activeId.set(conversationId);
        void this.router.navigate([], { queryParams: { conversation: conversationId }, replaceUrl: true });
      }
    } catch (e) {
      this.pending.set(null);
      this.error.set(problemMessage(e));
      return;
    }
    this.draft.set('');
    this.stream = this.answers.ask(workspaceId, conversationId, question).subscribe({
      next: (event) => {
        switch (event.type) {
          case 'start':
            this.messages.update((list) => [...list, event.question]);
            this.pending.set({ id: event.answerId, text: '' });
            break;
          case 'delta':
            this.pending.update((p) => (p ? { ...p, text: p.text + event.text } : p));
            break;
          case 'done':
            this.finish(event.answer);
            break;
          case 'error':
            this.finish(event.answer);
            this.error.set(event.detail);
            break;
        }
        this.scrollToEnd();
      },
      error: (e: unknown) => {
        this.pending.set(null);
        if (e instanceof AskRejectedError) {
          this.draft.set(question); // nothing was stored: let the user fix and resend
          this.error.set(e.message);
        } else {
          this.error.set('The connection was lost while answering. Reload the conversation to see what was saved.');
        }
      },
      complete: () => void this.refreshList(),
    });
  }

  private finish(answer: ChatMessage | null): void {
    if (answer) {
      this.messages.update((list) => [...list, answer]);
    }
    this.pending.set(null);
  }

  /** Stops the answer being generated; the server cancels the model and stores what it had. */
  protected stop(): void {
    const partial = this.pending();
    this.stream?.unsubscribe();
    this.stream = null;
    if (partial) {
      this.pending.set(null);
      this.messages.update((list) => [
        ...list,
        {
          id: partial.id ?? 'stopped',
          role: 'ASSISTANT',
          content: partial.text ? `${partial.text}\n\n(Stopped.)` : '(Stopped.)',
          status: 'FAILED',
          createdAt: new Date().toISOString(),
          citations: [],
        },
      ]);
    }
  }

  protected async remove(conversation: ConversationSummary): Promise<void> {
    const deleted = await this.confirm.open({
      title: `Delete “${conversation.title}”?`,
      message: 'The questions and answers of this conversation will be permanently deleted.',
      variant: 'danger',
      confirmLabel: 'Delete conversation',
      busyLabel: 'Deleting…',
      action: () => firstValueFrom(this.chat.delete(this.workspaceId(), conversation.id)),
    });
    if (deleted) {
      this.conversations.update((list) => list.filter((c) => c.id !== conversation.id));
      if (this.activeId() === conversation.id) {
        this.newConversation();
      }
    }
  }

  protected async openCitation(citation: Citation): Promise<void> {
    if (!citation.documentId) {
      return;
    }
    this.error.set(null);
    try {
      await this.opener.open(
        this.workspaceId(),
        { id: citation.documentId, filename: citation.documentName, contentType: citation.contentType },
        citation.pageNumber,
      );
    } catch (e) {
      this.error.set(problemMessage(e));
    }
  }

  private async refreshList(): Promise<void> {
    try {
      this.conversations.set(await firstValueFrom(this.chat.list(this.workspaceId())));
    } catch {
      // The list is refreshed only for titles and order; the answer is already shown.
    }
  }

  private scrollToEnd(): void {
    setTimeout(() => {
      const el = this.scroller()?.nativeElement;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }

  protected label(citation: Citation): string {
    return citationLabel(citation);
  }

  protected openLabel(citation: Citation): string {
    if (citation.contentType === 'application/pdf' && citation.pageNumber) {
      return `Open document at page ${citation.pageNumber}`;
    }
    return citation.contentType.startsWith('text/') || citation.contentType === 'application/pdf'
      ? 'Open document'
      : 'Download document';
  }

  protected answerClass(message: ChatMessage): string {
    if (message.status === 'FAILED') {
      return 'bg-red-50 text-red-900';
    }
    return message.content === NOT_FOUND_ANSWER ? 'bg-amber-50 text-amber-900' : 'bg-slate-100 text-slate-800';
  }
}
