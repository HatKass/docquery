import {
  ApplicationRef,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EnvironmentInjector,
  Injectable,
  computed,
  createComponent,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { problemMessage } from './problem';

export type ConfirmVariant = 'default' | 'danger';

export interface ConfirmOptions {
  /** The question, e.g. "Delete CIN.pdf?". Labels the dialog. */
  title: string;
  /** What will happen, e.g. "The file, its text chunks and embeddings will be permanently removed." */
  message: string;
  /** `danger` (red confirm button, Cancel focused first) for destructive or risky actions. */
  variant?: ConfirmVariant;
  confirmLabel?: string;
  /** Shown on the confirm button while {@link action} runs, e.g. "Deleting…". */
  busyLabel?: string;
  cancelLabel?: string;
  /**
   * Runs when the user confirms. The dialog stays open with a busy confirm button until it settles;
   * if it fails, the error is shown inside the dialog and the user can retry or cancel.
   */
  action?: () => Promise<unknown>;
}

let nextId = 0;

/**
 * Modal confirmation built on the native `<dialog>` (`showModal()`): the rest of the page becomes
 * inert, Escape cancels. On top of that, Tab is kept inside the dialog and focus goes to Cancel for
 * danger actions (the safe choice) or to Confirm otherwise. Open it through {@link ConfirmDialog}.
 */
@Component({
  selector: 'app-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog
      #dialog
      class="m-auto max-h-[calc(100dvh-2rem)] w-[min(28rem,calc(100vw-2rem))] overflow-y-auto rounded-xl border border-slate-200 bg-white p-0 text-left shadow-xl backdrop:bg-slate-900/40"
      data-testid="confirm-dialog"
      [attr.data-variant]="variant()"
      [attr.aria-labelledby]="titleId"
      [attr.aria-describedby]="messageId"
      (cancel)="onEscape($event)"
      (keydown)="onKeydown($event)"
    >
      <div class="flex gap-4 p-6">
        @if (variant() === 'danger') {
          <span
            class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-red-100 text-lg font-bold text-red-700"
            aria-hidden="true"
            >!</span
          >
        }
        <div class="min-w-0 flex-1">
          <h2 [id]="titleId" class="break-words text-base font-semibold text-slate-900" data-testid="confirm-title">
            {{ options().title }}
          </h2>
          <p [id]="messageId" class="mt-2 text-sm text-slate-600" data-testid="confirm-message">{{ options().message }}</p>
          @if (error()) {
            <p class="mt-3 rounded-md bg-red-50 p-3 text-sm text-red-800" role="alert" data-testid="confirm-error">
              {{ error() }}
            </p>
          }
        </div>
      </div>
      <div class="flex justify-end gap-2 rounded-b-xl border-t border-slate-100 bg-slate-50 px-6 py-3">
        <button
          #cancelButton
          type="button"
          class="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:opacity-50"
          data-testid="confirm-cancel"
          [disabled]="busy()"
          (click)="finish(false)"
        >
          {{ options().cancelLabel ?? 'Cancel' }}
        </button>
        <button
          #confirmButton
          type="button"
          data-testid="confirm-accept"
          [class]="confirmClass()"
          [disabled]="busy()"
          [attr.aria-busy]="busy()"
          (click)="accept()"
        >
          @if (busy()) {
            <span
              class="mr-2 inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/40 border-t-white align-[-2px]"
              aria-hidden="true"
            ></span>
            {{ options().busyLabel ?? 'Working…' }}
          } @else {
            {{ options().confirmLabel ?? 'Confirm' }}
          }
        </button>
      </div>
    </dialog>
  `,
})
export class ConfirmDialogComponent {
  readonly options = input.required<ConfirmOptions>();
  /** Emits once: true if confirmed (and the action succeeded), false if cancelled. */
  readonly closed = output<boolean>();

  protected readonly titleId = `confirm-title-${++nextId}`;
  protected readonly messageId = `confirm-message-${nextId}`;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly variant = computed(() => this.options().variant ?? 'default');
  protected readonly confirmClass = computed(
    () =>
      'rounded-md px-4 py-2 text-sm font-medium text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:opacity-70 ' +
      (this.variant() === 'danger'
        ? 'bg-red-600 hover:bg-red-700 focus-visible:ring-red-500'
        : 'bg-indigo-600 hover:bg-indigo-700 focus-visible:ring-indigo-500'),
  );

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly cancelButton = viewChild.required<ElementRef<HTMLButtonElement>>('cancelButton');
  private readonly confirmButton = viewChild.required<ElementRef<HTMLButtonElement>>('confirmButton');
  private done = false;

  /** Opens modally and moves focus into the dialog. */
  show(): void {
    const dialog = this.dialog().nativeElement;
    if (typeof dialog.showModal === 'function') {
      dialog.showModal();
    } else {
      dialog.setAttribute('open', ''); // environments without modal dialog support (e.g. old test DOMs)
    }
    (this.variant() === 'danger' ? this.cancelButton() : this.confirmButton()).nativeElement.focus();
  }

  protected async accept(): Promise<void> {
    const action = this.options().action;
    if (!action) {
      this.finish(true);
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
      this.finish(true);
    } catch (e) {
      this.busy.set(false);
      this.error.set(problemMessage(e));
      queueMicrotask(() => this.confirmButton().nativeElement.focus());
    }
  }

  /** Escape: the native dialog would close itself; route it through Cancel (and not while busy). */
  protected onEscape(event: Event): void {
    event.preventDefault();
    if (!this.busy()) {
      this.finish(false);
    }
  }

  /** Keeps Tab / Shift+Tab cycling between the dialog's own controls. */
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      // Some environments do not fire the native "cancel" event.
      this.onEscape(event);
      return;
    }
    if (event.key !== 'Tab') {
      return;
    }
    const focusable = Array.from(
      this.dialog().nativeElement.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input, select, textarea'),
    );
    if (focusable.length === 0) {
      event.preventDefault();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = this.dialog().nativeElement.ownerDocument.activeElement;
    if (event.shiftKey && (active === first || !focusable.includes(active as HTMLElement))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || !focusable.includes(active as HTMLElement))) {
      event.preventDefault();
      first.focus();
    }
  }

  finish(result: boolean): void {
    if (this.done) {
      return;
    }
    this.done = true;
    const dialog = this.dialog().nativeElement;
    if (typeof dialog.close === 'function' && dialog.open) {
      dialog.close();
    } else {
      dialog.removeAttribute('open');
    }
    this.closed.emit(result);
  }
}

/**
 * Asks the user to confirm, with a modal {@link ConfirmDialogComponent} created on demand and
 * removed afterwards. Focus returns to the element that had it (the triggering button) on close.
 *
 * ```ts
 * if (await this.confirm.open({ title: 'Delete x?', message: '…', variant: 'danger', action: () => … })) { … }
 * ```
 */
@Injectable({ providedIn: 'root' })
export class ConfirmDialog {
  private readonly appRef = inject(ApplicationRef);
  private readonly injector = inject(EnvironmentInjector);
  private readonly document = inject(DOCUMENT);

  /** @returns true if confirmed (and the action, if any, succeeded); false if cancelled */
  open(options: ConfirmOptions): Promise<boolean> {
    const opener = this.document.activeElement instanceof HTMLElement ? this.document.activeElement : null;
    const ref = createComponent(ConfirmDialogComponent, { environmentInjector: this.injector });
    ref.setInput('options', options);
    this.appRef.attachView(ref.hostView);
    this.document.body.appendChild(ref.location.nativeElement);
    ref.changeDetectorRef.detectChanges();
    return new Promise<boolean>((resolve) => {
      const subscription = ref.instance.closed.subscribe((result) => {
        subscription.unsubscribe();
        this.appRef.detachView(ref.hostView);
        ref.destroy();
        if (opener?.isConnected) {
          opener.focus();
        }
        resolve(result);
      });
      ref.instance.show();
    });
  }
}
