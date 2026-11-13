import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { formatCost, formatTokens } from './usage-format';
import { DailyUsage } from './usage.model';

export type UsageMetric = 'estimatedCost' | 'answers' | 'chatTokens' | 'embeddingTokens';

const METRICS: { id: UsageMetric; label: string }[] = [
  { id: 'estimatedCost', label: 'Estimated cost' },
  { id: 'answers', label: 'Answers' },
  { id: 'chatTokens', label: 'Chat tokens' },
  { id: 'embeddingTokens', label: 'Embedding tokens' },
];

const WIDTH = 600;
const HEIGHT = 160;
const TOP = 8;
const GAP = 2;

/**
 * Daily usage as a bar chart: one measure at a time (picked above the chart), so there is one series
 * and one y-scale. Each bar has a hover/focus tooltip; the same numbers are available as a table.
 * Plain SVG: no chart library for a single bar chart.
 */
@Component({
  selector: 'app-usage-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h4 class="text-sm font-medium text-slate-700" data-testid="chart-title">
        {{ metricLabel() }} per day (UTC)
      </h4>
      <div class="flex flex-wrap gap-1" role="group" aria-label="Measure">
        @for (m of metrics; track m.id) {
          <button
            type="button"
            class="rounded-md border px-2 py-1 text-xs"
            [class]="
              m.id === metric()
                ? 'border-indigo-600 bg-indigo-50 text-indigo-700'
                : 'border-slate-300 text-slate-600 hover:bg-slate-50'
            "
            [attr.aria-pressed]="m.id === metric()"
            (click)="metric.set(m.id)"
          >
            {{ m.label }}
          </button>
        }
      </div>
    </div>
    <div class="relative mt-3">
      <svg
        [attr.viewBox]="'0 0 ' + width + ' ' + (height + 20)"
        class="h-auto w-full"
        role="img"
        [attr.aria-label]="ariaLabel()"
      >
        <!-- Recessive grid: baseline and the maximum. -->
        <line
          [attr.x1]="0"
          [attr.x2]="width"
          [attr.y1]="height"
          [attr.y2]="height"
          class="stroke-slate-300"
          stroke-width="1"
        />
        <line
          [attr.x1]="0"
          [attr.x2]="width"
          [attr.y1]="top"
          [attr.y2]="top"
          class="stroke-slate-200"
          stroke-width="1"
          stroke-dasharray="3 3"
        />
        @for (bar of bars(); track bar.date) {
          @if (bar.h > 0) {
            <path
              [attr.d]="bar.path"
              class="fill-indigo-600"
              [class.opacity-60]="hovered() !== null && hovered() !== $index"
            />
          }
          <!-- Hit target: the whole column, taller and wider than the bar. -->
          <rect
            [attr.x]="bar.x - gap / 2"
            [attr.y]="0"
            [attr.width]="bar.w + gap"
            [attr.height]="height"
            fill="transparent"
            tabindex="0"
            [attr.aria-label]="bar.date + ': ' + bar.label"
            (mouseenter)="hovered.set($index)"
            (mouseleave)="hovered.set(null)"
            (focus)="hovered.set($index)"
            (blur)="hovered.set(null)"
          />
        }
        <text [attr.x]="0" [attr.y]="height + 15" class="fill-slate-500 text-[11px]">
          {{ firstDate() }}
        </text>
        <text
          [attr.x]="width"
          [attr.y]="height + 15"
          text-anchor="end"
          class="fill-slate-500 text-[11px]"
        >
          {{ lastDate() }}
        </text>
      </svg>
      <span
        class="pointer-events-none absolute left-1 top-0 rounded bg-white/80 px-1 text-[11px] text-slate-500"
      >
        max {{ maxLabel() }}
      </span>
      @if (tooltip(); as t) {
        <div
          class="pointer-events-none absolute top-6 z-10 -translate-x-1/2 whitespace-nowrap rounded-md border border-slate-200 bg-white px-2 py-1 text-xs text-slate-800 shadow"
          [style.left.%]="t.leftPercent"
          data-testid="chart-tooltip"
        >
          <div class="font-medium">{{ t.date }}</div>
          <div class="text-slate-600">{{ metricLabel() }}: {{ t.label }}</div>
        </div>
      }
    </div>
    <details class="mt-2 text-xs text-slate-600">
      <summary class="cursor-pointer select-none">Show as table</summary>
      <table class="mt-2 w-full text-left" data-testid="chart-table">
        <thead class="text-slate-500">
          <tr>
            <th class="py-1">Day</th>
            <th>Answers</th>
            <th>Chat tokens</th>
            <th>Embedding tokens</th>
            <th>Est. cost</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          @for (d of daily(); track d.date) {
            <tr>
              <td class="py-1">{{ d.date }}</td>
              <td>{{ d.answers }}</td>
              <td>{{ formatTokens(d.chatTokens) }}</td>
              <td>{{ formatTokens(d.embeddingTokens) }}</td>
              <td>{{ formatCost(d.estimatedCost) }}</td>
            </tr>
          }
        </tbody>
      </table>
    </details>
  `,
})
export class UsageChart {
  readonly daily = input.required<DailyUsage[]>();

  protected readonly metrics = METRICS;
  protected readonly metric = signal<UsageMetric>('estimatedCost');
  protected readonly hovered = signal<number | null>(null);
  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly top = TOP;
  protected readonly gap = GAP;
  protected readonly formatCost = formatCost;
  protected readonly formatTokens = formatTokens;

  protected readonly metricLabel = computed(
    () => METRICS.find((m) => m.id === this.metric())!.label,
  );

  private readonly max = computed(() => Math.max(0, ...this.daily().map((d) => d[this.metric()])));

  protected readonly maxLabel = computed(() => this.format(this.max()));

  protected readonly bars = computed(() => {
    const days = this.daily();
    const max = this.max();
    const slot = WIDTH / Math.max(1, days.length);
    const w = Math.max(1, Math.min(24, slot - GAP));
    return days.map((d, i) => {
      const value = d[this.metric()];
      const h = max > 0 ? Math.max(value > 0 ? 2 : 0, ((HEIGHT - TOP) * value) / max) : 0;
      const x = i * slot + (slot - w) / 2;
      return {
        date: d.date,
        x,
        w,
        h,
        label: this.format(value),
        path: roundedTopBar(x, HEIGHT - h, w, h, Math.min(4, w / 2, h)),
      };
    });
  });

  protected readonly tooltip = computed(() => {
    const i = this.hovered();
    const bar = i === null ? undefined : this.bars()[i];
    return bar
      ? {
          date: bar.date,
          label: bar.label,
          leftPercent: Math.min(90, Math.max(10, ((bar.x + bar.w / 2) / WIDTH) * 100)),
        }
      : null;
  });

  protected readonly firstDate = computed(() => this.daily()[0]?.date ?? '');
  protected readonly lastDate = computed(() => this.daily().at(-1)?.date ?? '');

  protected readonly ariaLabel = computed(
    () =>
      `${this.metricLabel()} per day from ${this.firstDate()} to ${this.lastDate()}; maximum ${this.maxLabel()}. A table view follows.`,
  );

  private format(value: number): string {
    switch (this.metric()) {
      case 'estimatedCost':
        return formatCost(value);
      case 'answers':
        return `${value}`;
      default:
        return formatTokens(value);
    }
  }
}

/** A bar with rounded top corners, anchored square on the baseline. */
function roundedTopBar(x: number, y: number, w: number, h: number, r: number): string {
  return `M${x},${y + h} V${y + r} Q${x},${y} ${x + r},${y} H${x + w - r} Q${x + w},${y} ${x + w},${y + r} V${y + h} Z`;
}
