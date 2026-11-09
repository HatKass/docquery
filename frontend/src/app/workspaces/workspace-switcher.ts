import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { WorkspaceContext } from './workspace-context.service';

/** Header dropdown to switch between the workspaces the user belongs to. */
@Component({
  selector: 'app-workspace-switcher',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex items-center gap-2">
      <label for="workspace-switcher" class="sr-only">Current workspace</label>
      @if (context.workspaces().length) {
        <select
          id="workspace-switcher"
          data-testid="workspace-switcher"
          class="rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm text-slate-800"
          [value]="context.current()?.id ?? ''"
          (change)="switchTo($any($event.target).value)"
        >
          @for (w of context.workspaces(); track w.id) {
            <option [value]="w.id" [selected]="w.id === context.current()?.id">{{ w.name }} · {{ w.role }}</option>
          }
        </select>
      } @else {
        <span class="text-sm text-slate-500">No workspace yet</span>
      }
      <a routerLink="/workspaces" class="text-sm text-indigo-700 hover:underline">Manage</a>
    </div>
  `,
})
export class WorkspaceSwitcher {
  protected readonly context = inject(WorkspaceContext);
  private readonly router = inject(Router);

  protected switchTo(id: string): void {
    this.context.select(id);
    void this.router.navigate(['/workspaces', id]);
  }
}
