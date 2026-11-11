import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { problemMessage } from '../shared/problem';
import { WorkspaceContext } from './workspace-context.service';

/** All workspaces of the signed-in user, plus "create workspace". */
@Component({
  selector: 'app-workspaces-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-3xl space-y-6">
      <div class="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <h2 class="text-lg font-semibold text-slate-900">Your workspaces</h2>
        @if (!context.loaded()) {
          <p class="mt-4 text-sm text-slate-500">Loading…</p>
        } @else if (context.workspaces().length === 0) {
          <p class="mt-4 text-sm text-slate-600" data-testid="no-workspaces">
            You are not a member of any workspace yet. Create one below, or ask an owner to add you.
          </p>
        } @else {
          <ul class="mt-4 divide-y divide-slate-100" data-testid="workspace-list">
            @for (w of context.workspaces(); track w.id) {
              <li class="flex items-center justify-between py-3">
                <a [routerLink]="['/workspaces', w.id]" class="font-medium text-indigo-700 hover:underline">{{ w.name }}</a>
                <span class="text-sm text-slate-500">
                  {{ w.role }} · {{ w.memberCount }} {{ w.memberCount === 1 ? 'member' : 'members' }}
                </span>
              </li>
            }
          </ul>
        }
      </div>

      <form class="rounded-xl border border-slate-200 bg-white p-6 shadow-sm" (submit)="create($event)">
        <h3 class="font-semibold text-slate-900">Create a workspace</h3>
        <p class="mt-1 text-sm text-slate-500">You become its owner and can add colleagues afterwards.</p>
        <div class="mt-4 flex gap-2">
          <label for="new-workspace-name" class="sr-only">Workspace name</label>
          <input
            id="new-workspace-name"
            data-testid="new-workspace-name"
            class="flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm"
            maxlength="100"
            placeholder="e.g. Legal team"
            [value]="name()"
            (input)="name.set($any($event.target).value)"
          />
          <button
            type="submit"
            class="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
            [disabled]="busy() || !name().trim()"
          >
            Create
          </button>
        </div>
        @if (error()) {
          <p class="mt-3 text-sm text-red-700" role="alert">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class WorkspacesPage implements OnInit {
  protected readonly context = inject(WorkspaceContext);
  private readonly router = inject(Router);

  protected readonly name = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.context.load().catch((e) => this.error.set(problemMessage(e)));
  }

  protected async create(event: Event): Promise<void> {
    event.preventDefault();
    this.busy.set(true);
    this.error.set(null);
    try {
      const created = await this.context.create(this.name().trim());
      this.name.set('');
      await this.router.navigate(['/workspaces', created.id]);
    } catch (e) {
      this.error.set(problemMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
}
