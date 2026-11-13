import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * Shown in place of a list or panel whose data could not be loaded, so a failed request never looks
 * like "nothing here yet". Friendly message from the API, plus a retry.
 */
@Component({
  selector: 'app-load-error',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="flex flex-col gap-3 rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800 sm:flex-row sm:items-center sm:justify-between"
      role="alert"
      data-testid="load-error"
    >
      <div class="min-w-0">
        <p class="font-medium">Couldn't load {{ what() }}.</p>
        <p class="mt-0.5 break-words text-red-700">{{ message() }}</p>
      </div>
      <button
        type="button"
        class="shrink-0 self-start rounded-md border border-red-300 bg-white px-3 py-1.5 font-medium text-red-800 hover:bg-red-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-500 sm:self-auto"
        data-testid="load-error-retry"
        (click)="retry.emit()"
      >
        Try again
      </button>
    </div>
  `,
})
export class LoadError {
  /** What failed to load, e.g. "documents" → "Couldn't load documents." */
  readonly what = input.required<string>();
  readonly message = input.required<string>();
  readonly retry = output<void>();
}
