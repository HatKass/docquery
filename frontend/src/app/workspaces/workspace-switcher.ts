import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { RoleBadge } from '../shell/role-badge';
import { WorkspaceContext } from './workspace-context.service';

/** Workspace sections; switching workspace keeps the user in the same one. */
const SECTION = /^\/workspaces\/[^/?#]+\/(chat|members)(?:[/?#]|$)/;

/**
 * Switch between the workspaces the user belongs to, with their role in the current one as a badge.
 * A native select: keyboard, screen reader and mobile pickers work out of the box.
 */
@Component({
  selector: 'app-workspace-switcher',
  imports: [RouterLink, RoleBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex min-w-0 items-center gap-2">
      @if (context.workspaces().length) {
        <label for="workspace-switcher" class="sr-only">Current workspace</label>
        <select
          id="workspace-switcher"
          data-testid="workspace-switcher"
          class="min-w-0 flex-1 truncate rounded-md border border-slate-300 bg-white py-1.5 pl-2.5 pr-8 text-sm font-medium text-slate-800 hover:border-slate-400 focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 lg:max-w-64 lg:flex-none"
          [value]="context.current()?.id ?? ''"
          (change)="switchTo($any($event.target).value)"
        >
          @for (w of context.workspaces(); track w.id) {
            <option [value]="w.id" [selected]="w.id === context.current()?.id">{{ w.name }}</option>
          }
        </select>
        @if (context.current(); as current) {
          <app-role-badge [role]="current.role" [attr.title]="'Your role in ' + current.name" />
        }
      } @else if (context.loaded()) {
        <a
          routerLink="/workspaces"
          class="rounded-md px-2 py-1.5 text-sm text-indigo-700 hover:bg-indigo-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
          >Create a workspace</a
        >
      }
    </div>
  `,
})
export class WorkspaceSwitcher {
  protected readonly context = inject(WorkspaceContext);
  private readonly router = inject(Router);

  protected switchTo(id: string): void {
    this.context.select(id);
    const section = SECTION.exec(this.router.url)?.[1];
    void this.router.navigate(section ? ['/workspaces', id, section] : ['/workspaces', id]);
  }
}
