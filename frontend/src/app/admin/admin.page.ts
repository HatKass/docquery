import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ConfirmDialog } from '../shared/confirm-dialog';
import { problemMessage } from '../shared/problem';
import { UsageReportView } from '../usage/usage-report';
import { UsageReport } from '../usage/usage.model';
import { AdminApi } from './admin-api.service';
import {
  AdminOverview,
  AdminUser,
  DeadLetter,
  DeadLetterStatus,
  FailedDocument,
} from './admin.model';

const STATUS_ORDER = ['UPLOADED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING', 'READY', 'FAILED'];

/**
 * Platform admin page: pipeline health, usage and cost of all workspaces, failed documents,
 * dead-lettered ingestion events (retry / dismiss) and per-user workspace limits.
 */
@Component({
  selector: 'app-admin-page',
  imports: [DatePipe, UsageReportView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-6xl space-y-6">
      <h2 class="text-xl font-semibold text-slate-900">Admin</h2>
      @if (notice(); as n) {
        <p
          class="rounded-md px-3 py-2 text-sm"
          [class]="n.error ? 'bg-red-50 text-red-800' : 'bg-emerald-50 text-emerald-800'"
          role="status"
          data-testid="admin-notice"
        >
          {{ n.text }}
        </p>
      }

      <!-- Overview -->
      @if (overview(); as o) {
        <div
          class="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6"
          data-testid="admin-overview"
        >
          <h3 class="font-semibold text-slate-900">Pipeline</h3>
          <dl class="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-8">
            @for (s of statusOrder; track s) {
              <div class="rounded-lg bg-slate-50 p-3">
                <dt class="text-xs text-slate-500">{{ s }}</dt>
                <dd
                  class="mt-1 text-lg font-semibold"
                  [class]="
                    s === 'FAILED' && o.documentsByStatus[s] ? 'text-red-700' : 'text-slate-900'
                  "
                >
                  {{ o.documentsByStatus[s] ?? 0 }}
                </dd>
              </div>
            }
            <div class="rounded-lg bg-slate-50 p-3">
              <dt class="text-xs text-slate-500">Dead letters</dt>
              <dd
                class="mt-1 text-lg font-semibold"
                [class]="o.pendingDeadLetters ? 'text-red-700' : 'text-slate-900'"
              >
                {{ o.pendingDeadLetters }}
              </dd>
            </div>
            <div class="rounded-lg bg-slate-50 p-3">
              <dt class="text-xs text-slate-500">Users / workspaces</dt>
              <dd class="mt-1 text-lg font-semibold text-slate-900">
                {{ o.users }} / {{ o.workspaces }}
              </dd>
            </div>
          </dl>
        </div>
      }

      <!-- Usage -->
      <div class="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h3 class="font-semibold text-slate-900">Usage &amp; cost, all workspaces</h3>
          <label class="text-sm text-slate-600">
            Period
            <select
              class="ml-1 rounded-md border border-slate-300 px-2 py-1 text-sm"
              [value]="days()"
              (change)="setDays(+$any($event.target).value)"
              data-testid="usage-days"
            >
              <option value="7">7 days</option>
              <option value="30">30 days</option>
              <option value="90">90 days</option>
            </select>
          </label>
        </div>
        <div class="mt-4">
          @if (usage(); as u) {
            <app-usage-report [report]="u" [showWorkspaces]="true" />
          } @else {
            <p class="text-sm text-slate-500">Loading…</p>
          }
        </div>
      </div>

      <!-- Failed documents -->
      <div class="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
        <h3 class="font-semibold text-slate-900">Failed documents</h3>
        <p class="mt-1 text-sm text-slate-500">
          Documents whose ingestion failed, in any workspace. Retry queues them again.
        </p>
        <ul class="mt-3 divide-y divide-slate-100" data-testid="failures">
          @for (f of failures(); track f.documentId) {
            <li class="flex flex-col gap-2 py-3 sm:flex-row sm:items-start sm:justify-between">
              <div class="min-w-0">
                <p class="truncate text-sm font-medium text-slate-800">{{ f.filename }}</p>
                <p class="text-xs text-slate-500">
                  {{ f.workspaceName }} · {{ f.uploadedBy ?? 'deleted user' }} ·
                  {{ f.failedAt | date: 'medium' }}
                </p>
                <p class="mt-1 text-sm text-red-700">{{ f.failureReason }}</p>
              </div>
              <button
                type="button"
                class="shrink-0 rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50"
                [disabled]="busy()"
                (click)="retryDocument(f)"
              >
                Retry
              </button>
            </li>
          } @empty {
            <li class="py-3 text-sm text-slate-500">No failed documents.</li>
          }
        </ul>
      </div>

      <!-- Dead letters -->
      <div class="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h3 class="font-semibold text-slate-900">Dead-letter events</h3>
          <label class="text-sm text-slate-600">
            Show
            <select
              class="ml-1 rounded-md border border-slate-300 px-2 py-1 text-sm"
              [value]="deadLetterFilter()"
              (change)="setDeadLetterFilter($any($event.target).value)"
              data-testid="dead-letter-filter"
            >
              <option value="PENDING">Pending</option>
              <option value="RETRIED">Retried</option>
              <option value="DISMISSED">Dismissed</option>
              <option value="ALL">All</option>
            </select>
          </label>
        </div>
        <p class="mt-1 text-sm text-slate-500">
          Ingestion events the worker gave up on (topic document.uploaded.dlt). Retry queues the
          document again with a new event.
        </p>
        <ul class="mt-3 divide-y divide-slate-100" data-testid="dead-letters">
          @for (d of deadLetters(); track d.id) {
            <li class="py-3">
              <div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                <div class="min-w-0">
                  <p class="truncate text-sm font-medium text-slate-800">
                    {{ d.documentName ?? (d.documentId ? 'Deleted document' : 'Unreadable event') }}
                    <span
                      class="ml-1 rounded bg-slate-100 px-1.5 py-0.5 text-xs font-normal text-slate-600"
                      >{{ d.status }}</span
                    >
                  </p>
                  <p class="text-xs text-slate-500">
                    {{ d.workspaceName ?? '—' }} · failed {{ d.failedAt | date: 'medium' }}
                    @if (d.documentStatus) {
                      · document is {{ d.documentStatus }}
                    }
                    @if (d.resolvedAt) {
                      · {{ d.status === 'RETRIED' ? 'retried' : 'dismissed' }} by
                      {{ d.resolvedBy ?? '?' }} {{ d.resolvedAt | date: 'short' }}
                    }
                  </p>
                  <p class="mt-1 break-words text-sm text-red-700" data-testid="dead-letter-error">
                    {{ d.errorSummary }}
                  </p>
                </div>
                @if (d.status === 'PENDING') {
                  <div class="flex shrink-0 flex-col items-start gap-1 sm:items-end">
                    <div class="flex gap-2">
                      <button
                        type="button"
                        class="rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white hover:bg-indigo-700 disabled:opacity-50"
                        [disabled]="busy() || !d.retryable"
                        [title]="d.retryable ? 'Queue the document again' : retryBlockedReason(d)"
                        [attr.aria-describedby]="d.retryable ? null : 'retry-hint-' + d.id"
                        (click)="retryDeadLetter(d)"
                      >
                        Retry
                      </button>
                      <button
                        type="button"
                        class="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50"
                        [disabled]="busy()"
                        (click)="dismissDeadLetter(d)"
                      >
                        Dismiss
                      </button>
                    </div>
                    @if (!d.retryable) {
                      <p
                        class="text-xs text-amber-700"
                        [id]="'retry-hint-' + d.id"
                        data-testid="retry-hint"
                      >
                        {{ retryBlockedReason(d) }}
                      </p>
                    }
                  </div>
                }
              </div>
              <details class="mt-2 text-xs text-slate-600">
                <summary class="cursor-pointer select-none">Details</summary>
                <dl class="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
                  <dt class="text-slate-500">Exception</dt>
                  <dd class="break-all font-mono">{{ d.exceptionClass ?? '—' }}</dd>
                  <dt class="text-slate-500">Message</dt>
                  <dd class="break-words font-mono" data-testid="dead-letter-full-message">
                    {{ d.exceptionMessage ?? '—' }}
                  </dd>
                  <dt class="text-slate-500">Original</dt>
                  <dd class="font-mono">
                    {{ d.originalTopic ?? '?' }}-{{ d.originalPartition ?? '?' }}&#64;{{
                      d.originalOffset ?? '?'
                    }}
                  </dd>
                  <dt class="text-slate-500">Event id</dt>
                  <dd class="break-all font-mono">{{ d.eventId ?? '—' }}</dd>
                </dl>
                <pre
                  class="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-all rounded bg-slate-50 p-2 font-mono"
                  >{{ d.payload }}</pre>
              </details>
            </li>
          } @empty {
            <li class="py-3 text-sm text-slate-500">
              No
              {{ deadLetterFilter() === 'ALL' ? '' : deadLetterFilter().toLowerCase() }} dead-letter
              events.
            </li>
          }
        </ul>
      </div>

      <!-- Users & workspace limits -->
      <div class="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
        <h3 class="font-semibold text-slate-900">Users &amp; workspace limits</h3>
        <p class="mt-1 text-sm text-slate-500">
          Users may create {{ overview()?.defaultWorkspaceLimit || 'unlimited' }} workspaces by
          default. Override it per user (0 = no limit).
        </p>
        <form class="mt-3 flex gap-2" (submit)="searchUsers($event)">
          <label for="user-query" class="sr-only">Search users</label>
          <input
            id="user-query"
            class="min-w-0 flex-1 rounded-md border border-slate-300 px-2 py-1.5 text-sm sm:max-w-xs"
            placeholder="Username, e-mail or name"
            [value]="userQuery()"
            (input)="userQuery.set($any($event.target).value)"
          />
          <button type="submit" class="rounded-md border border-slate-300 px-3 py-1.5 text-sm">
            Search
          </button>
        </form>
        <div class="mt-3 overflow-x-auto">
          <table class="w-full min-w-[36rem] text-left text-sm" data-testid="admin-users">
            <thead class="text-xs text-slate-500">
              <tr>
                <th class="py-1">User</th>
                <th>Workspaces</th>
                <th>Today (questions / uploads)</th>
                <th>Workspace limit</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              @for (u of users(); track u.id) {
                <tr>
                  <td class="py-2 pr-2">
                    <span class="block font-medium text-slate-800">{{ u.displayName }}</span>
                    <span class="block text-xs text-slate-500">{{ u.username }}</span>
                  </td>
                  <td>{{ u.workspacesCreated }} of {{ u.workspaceLimit ?? '∞' }}</td>
                  <td>{{ u.questionsToday }} / {{ u.uploadsToday }}</td>
                  <td>
                    <div class="flex items-center gap-1">
                      <label class="sr-only" [for]="'limit-' + u.id"
                        >Workspace limit for {{ u.username }}</label
                      >
                      <input
                        type="number"
                        min="0"
                        class="w-16 rounded-md border border-slate-300 px-1.5 py-1 text-sm"
                        [id]="'limit-' + u.id"
                        [value]="u.workspaceLimitOverride ?? ''"
                        placeholder="default"
                        #limit
                      />
                      <button
                        type="button"
                        class="rounded-md border border-slate-300 px-2 py-1 text-xs"
                        [disabled]="busy()"
                        (click)="setLimit(u, limit.value)"
                      >
                        Save
                      </button>
                      @if (u.workspaceLimitOverride !== null) {
                        <button
                          type="button"
                          class="rounded-md px-2 py-1 text-xs text-slate-600 hover:underline"
                          [disabled]="busy()"
                          (click)="setLimit(u, '')"
                        >
                          Use default
                        </button>
                      }
                    </div>
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="4" class="py-2 text-slate-500">No users found.</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </section>
  `,
})
export class AdminPage {
  private readonly api = inject(AdminApi);
  private readonly confirm = inject(ConfirmDialog);

  protected readonly statusOrder = STATUS_ORDER;
  protected readonly overview = signal<AdminOverview | null>(null);
  protected readonly usage = signal<UsageReport | null>(null);
  protected readonly days = signal(30);
  protected readonly failures = signal<FailedDocument[]>([]);
  protected readonly deadLetters = signal<DeadLetter[]>([]);
  protected readonly deadLetterFilter = signal<DeadLetterStatus | 'ALL'>('PENDING');
  protected readonly users = signal<AdminUser[]>([]);
  protected readonly userQuery = signal('');
  protected readonly busy = signal(false);
  protected readonly notice = signal<{ text: string; error: boolean } | null>(null);

  constructor() {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    await Promise.all([
      this.loadOverview(),
      this.loadUsage(),
      this.loadFailures(),
      this.loadDeadLetters(),
      this.loadUsers(),
    ]);
  }

  protected setDays(days: number): void {
    this.days.set(days);
    this.usage.set(null);
    void this.loadUsage();
  }

  protected setDeadLetterFilter(filter: DeadLetterStatus | 'ALL'): void {
    this.deadLetterFilter.set(filter);
    void this.loadDeadLetters();
  }

  protected searchUsers(event: Event): void {
    event.preventDefault();
    void this.loadUsers();
  }

  protected retryDocument(f: FailedDocument): Promise<void> {
    return this.act(
      () => firstValueFrom(this.api.reindexDocument(f.documentId)),
      `"${f.filename}" was queued again.`,
    );
  }

  protected retryDeadLetter(d: DeadLetter): Promise<void> {
    return this.act(
      () => firstValueFrom(this.api.retryDeadLetter(d.id)),
      `"${d.documentName}" was queued again.`,
    );
  }

  protected dismissDeadLetter(d: DeadLetter): Promise<void> {
    return this.act(
      () => firstValueFrom(this.api.dismissDeadLetter(d.id)),
      'The event was dismissed.',
    );
  }

  /** Why "Retry" is disabled for a pending event (shown next to the button). */
  protected retryBlockedReason(d: DeadLetter): string {
    if (!d.documentId) {
      return 'Event could not be read — inspect it, then dismiss it';
    }
    return d.documentStatus ? 'Nothing to retry' : 'Document was deleted — dismiss this event';
  }

  protected async setLimit(u: AdminUser, raw: string): Promise<void> {
    const value = raw.trim() === '' ? null : Number(raw);
    if (value !== null && (!Number.isInteger(value) || value < 0)) {
      this.notice.set({
        text: 'The limit must be a whole number, 0 or more (0 = no limit).',
        error: true,
      });
      return;
    }
    // The limit only applies to creating workspaces: below what the user already has, nothing is
    // deleted, but they are blocked until they are under it. Make sure the admin knows that.
    const effective = value ?? this.overview()?.defaultWorkspaceLimit ?? 0;
    if (effective > 0 && effective < u.workspacesCreated) {
      const confirmed = await this.confirm.open({
        title: `Lower ${u.displayName}'s workspace limit to ${effective}?`,
        message:
          `${u.displayName} already has ${u.workspacesCreated} workspaces. They keep all of them; only ` +
          `creating new workspaces is blocked until they have fewer than ${effective}.`,
        confirmLabel: 'Lower limit',
        busyLabel: 'Saving…',
        action: () => firstValueFrom(this.api.setWorkspaceLimit(u.id, value)),
      });
      if (confirmed) {
        this.notice.set({
          text: `${u.username} may now create at most ${effective} workspaces.`,
          error: false,
        });
        await this.refresh();
      }
      return;
    }
    const text =
      value === null
        ? `${u.username} now has the default limit.`
        : value === 0
          ? `${u.username} has no workspace limit.`
          : `${u.username} may create ${value} workspaces.`;
    return this.act(() => firstValueFrom(this.api.setWorkspaceLimit(u.id, value)), text);
  }

  private async act(action: () => Promise<unknown>, success: string): Promise<void> {
    this.busy.set(true);
    this.notice.set(null);
    try {
      await action();
      this.notice.set({ text: success, error: false });
    } catch (e) {
      this.notice.set({ text: problemMessage(e), error: true });
    } finally {
      this.busy.set(false);
    }
    await this.refresh();
  }

  private async loadOverview(): Promise<void> {
    await this.load(
      () => firstValueFrom(this.api.overview()),
      (o) => this.overview.set(o),
    );
  }

  private async loadUsage(): Promise<void> {
    await this.load(
      () => firstValueFrom(this.api.usage(this.days())),
      (u) => this.usage.set(u),
    );
  }

  private async loadFailures(): Promise<void> {
    await this.load(
      () => firstValueFrom(this.api.failures()),
      (f) => this.failures.set(f),
    );
  }

  private async loadDeadLetters(): Promise<void> {
    await this.load(
      () => firstValueFrom(this.api.deadLetters(this.deadLetterFilter())),
      (d) => this.deadLetters.set(d),
    );
  }

  private async loadUsers(): Promise<void> {
    await this.load(
      () => firstValueFrom(this.api.users(this.userQuery().trim())),
      (u) => this.users.set(u),
    );
  }

  private async load<T>(request: () => Promise<T>, apply: (value: T) => void): Promise<void> {
    try {
      apply(await request());
    } catch (e) {
      this.notice.set({ text: problemMessage(e), error: true });
    }
  }
}
