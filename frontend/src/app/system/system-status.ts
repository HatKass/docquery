import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { SystemStatusService } from './system-status.service';

@Component({
  selector: 'app-system-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-2xl rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
      <div class="flex items-center justify-between">
        <h2 class="text-lg font-semibold text-slate-900">System status</h2>
        <button
          type="button"
          class="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
          (click)="service.refresh()"
        >
          Refresh
        </button>
      </div>

      @switch (service.status().state) {
        @case ('loading') {
          <p class="mt-4 text-sm text-slate-500" data-testid="status-loading">Checking the API…</p>
        }
        @case ('unreachable') {
          <p class="mt-4 rounded-md bg-amber-50 p-3 text-sm text-amber-800" data-testid="status-unreachable">
            {{ unreachableMessage() }}
          </p>
        }
        @default {
          <p class="mt-4 text-sm" data-testid="status-overall">
            API:
            <span [class]="service.isUp() ? 'font-semibold text-emerald-700' : 'font-semibold text-red-700'">
              {{ overall() }}
            </span>
          </p>
          <ul class="mt-3 divide-y divide-slate-100 text-sm">
            @for (c of components(); track c.name) {
              <li class="flex justify-between py-2">
                <span class="text-slate-700">{{ c.name }}</span>
                <span [class]="c.status === 'UP' ? 'text-emerald-700' : 'text-red-700'">{{ c.status }}</span>
              </li>
            }
          </ul>
        }
      }
    </section>
  `,
})
export class SystemStatusPage implements OnInit {
  protected readonly service = inject(SystemStatusService);

  ngOnInit(): void {
    this.service.refresh();
  }

  protected overall(): string {
    const s = this.service.status();
    return s.state === 'up' || s.state === 'down' ? s.overall : '';
  }

  protected components(): { name: string; status: string }[] {
    const s = this.service.status();
    return s.state === 'up' || s.state === 'down' ? s.components : [];
  }

  protected unreachableMessage(): string {
    const s = this.service.status();
    return s.state === 'unreachable' ? s.message : '';
  }
}
