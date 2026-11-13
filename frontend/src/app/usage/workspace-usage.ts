import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { problemMessage } from '../shared/problem';
import { UsageApi } from './usage-api.service';
import { UsageReportView } from './usage-report';
import { UsageReport } from './usage.model';

/** "Usage & cost" card of a workspace (shown to owners: it lists what each member used). */
@Component({
  selector: 'app-workspace-usage',
  imports: [UsageReportView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6"
      data-testid="workspace-usage"
    >
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h3 class="font-semibold text-slate-900">Usage &amp; cost</h3>
        <label class="text-sm text-slate-600">
          Period
          <select
            class="ml-1 rounded-md border border-slate-300 px-2 py-1 text-sm"
            [value]="days()"
            (change)="days.set(+$any($event.target).value)"
          >
            <option value="7">7 days</option>
            <option value="30">30 days</option>
            <option value="90">90 days</option>
          </select>
        </label>
      </div>
      <div class="mt-4">
        @if (error(); as e) {
          <p class="text-sm text-red-700" role="alert">{{ e }}</p>
        } @else if (report(); as r) {
          <app-usage-report [report]="r" />
        } @else {
          <p class="text-sm text-slate-500">Loading…</p>
        }
      </div>
    </div>
  `,
})
export class WorkspaceUsage {
  readonly workspaceId = input.required<string>();

  private readonly api = inject(UsageApi);

  protected readonly days = signal(30);
  protected readonly report = signal<UsageReport | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const id = this.workspaceId();
      const days = this.days();
      untracked(() => void this.load(id, days));
    });
  }

  private async load(id: string, days: number): Promise<void> {
    this.report.set(null);
    this.error.set(null);
    try {
      this.report.set(await firstValueFrom(this.api.workspaceUsage(id, days)));
    } catch (e) {
      this.error.set(problemMessage(e));
    }
  }
}
