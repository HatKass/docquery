import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { UsageChart } from './usage-chart';
import { formatCost, formatTokens } from './usage-format';
import { UsageReport } from './usage.model';

/** Totals, a daily chart and breakdowns of one usage report (workspace owners and admins). */
@Component({
  selector: 'app-usage-report',
  imports: [UsageChart],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let r = report();
    <dl class="grid grid-cols-2 gap-3 sm:grid-cols-4" data-testid="usage-totals">
      <div class="rounded-lg bg-slate-50 p-3">
        <dt class="text-xs text-slate-500">Estimated cost</dt>
        <dd class="mt-1 text-lg font-semibold text-slate-900" data-testid="total-cost">
          {{ formatCost(r.totals.estimatedCost) }}
        </dd>
      </div>
      <div class="rounded-lg bg-slate-50 p-3">
        <dt class="text-xs text-slate-500">Answers</dt>
        <dd class="mt-1 text-lg font-semibold text-slate-900">{{ r.totals.answers }}</dd>
      </div>
      <div class="rounded-lg bg-slate-50 p-3">
        <dt class="text-xs text-slate-500">Chat tokens (in / out)</dt>
        <dd class="mt-1 text-lg font-semibold text-slate-900">
          {{ formatTokens(r.totals.chatInputTokens) }} /
          {{ formatTokens(r.totals.chatOutputTokens) }}
        </dd>
      </div>
      <div class="rounded-lg bg-slate-50 p-3">
        <dt class="text-xs text-slate-500">Embedding tokens</dt>
        <dd class="mt-1 text-lg font-semibold text-slate-900">
          {{ formatTokens(r.totals.embeddingTokens) }}
        </dd>
      </div>
    </dl>
    <p class="mt-2 text-xs text-slate-500">
      {{ r.from }} to {{ r.to }} (UTC). Costs are estimates from token counts and the configured
      model prices.
      @if (r.totals.estimatedTokens) {
        Some token counts were estimated because the model did not report usage.
      }
    </p>
    @if (r.unpricedModels.length) {
      <p
        class="mt-2 rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-800"
        role="status"
        data-testid="unpriced"
      >
        ⚠ No price configured for {{ r.unpricedModels.join(', ') }}: their usage is not included in
        the cost (set USAGE_PRICES).
      </p>
    }

    <div class="mt-5"><app-usage-chart [daily]="r.daily" /></div>

    <div class="mt-6 grid gap-6" [class.lg:grid-cols-2]="showWorkspaces()">
      @if (showWorkspaces()) {
        <div class="min-w-0">
          <h4 class="text-sm font-medium text-slate-700">By workspace</h4>
          <table class="mt-2 w-full text-left text-sm" data-testid="usage-by-workspace">
            <thead class="text-xs text-slate-500">
              <tr>
                <th class="py-1">Workspace</th>
                <th>Answers</th>
                <th>Tokens</th>
                <th>Est. cost</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              @for (w of r.workspaces; track w.workspaceId) {
                <tr>
                  <td class="max-w-0 truncate py-1.5 pr-2">
                    @if (w.name) {
                      {{ w.name }}
                    } @else {
                      <span class="italic text-slate-500">Deleted workspace</span>
                    }
                  </td>
                  <td>{{ w.answers }}</td>
                  <td>{{ formatTokens(w.tokens) }}</td>
                  <td>{{ formatCost(w.estimatedCost) }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="4" class="py-2 text-slate-500">No usage in this period.</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      <div class="min-w-0">
        <h4 class="text-sm font-medium text-slate-700">By user</h4>
        <table class="mt-2 w-full text-left text-sm" data-testid="usage-by-user">
          <thead class="text-xs text-slate-500">
            <tr>
              <th class="py-1">User</th>
              <th>Answers</th>
              <th>Tokens</th>
              <th>Est. cost</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            @for (u of r.users; track u.userId) {
              <tr>
                <td class="max-w-0 truncate py-1.5 pr-2">
                  @if (u.displayName) {
                    {{ u.displayName }}
                  } @else {
                    <span class="italic text-slate-500">Deleted user</span>
                  }
                </td>
                <td>{{ u.answers }}</td>
                <td>{{ formatTokens(u.tokens) }}</td>
                <td>{{ formatCost(u.estimatedCost) }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" class="py-2 text-slate-500">No usage in this period.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </div>

    <div class="mt-6 min-w-0">
      <h4 class="text-sm font-medium text-slate-700">By model</h4>
      <table class="mt-2 w-full text-left text-sm" data-testid="usage-by-model">
        <thead class="text-xs text-slate-500">
          <tr>
            <th class="py-1">Model</th>
            <th>Kind</th>
            <th>Input</th>
            <th>Output</th>
            <th>Est. cost</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          @for (m of r.models; track m.kind + m.model) {
            <tr>
              <td class="max-w-0 truncate py-1.5 pr-2 font-mono text-xs">{{ m.model }}</td>
              <td class="text-xs">{{ m.kind === 'CHAT' ? 'Chat' : 'Embedding' }}</td>
              <td>{{ formatTokens(m.inputTokens) }}</td>
              <td>{{ formatTokens(m.outputTokens) }}</td>
              <td>{{ m.estimatedCost === null ? 'unpriced' : formatCost(m.estimatedCost) }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="py-2 text-slate-500">No usage in this period.</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class UsageReportView {
  readonly report = input.required<UsageReport>();
  readonly showWorkspaces = input(false);

  protected readonly formatCost = formatCost;
  protected readonly formatTokens = formatTokens;
}
