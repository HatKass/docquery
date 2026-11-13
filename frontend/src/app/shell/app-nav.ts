import { ChangeDetectionStrategy, Component, inject, output } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { WorkspaceContext } from '../workspaces/workspace-context.service';
import { Icon, IconName } from './icon';

interface Section {
  path: string;
  label: string;
  icon: IconName;
  exact: boolean;
}

const SECTIONS: Section[] = [
  { path: '', label: 'Documents', icon: 'documents', exact: true },
  { path: 'chat', label: 'Ask', icon: 'chat', exact: false },
  { path: 'members', label: 'Members & settings', icon: 'members', exact: false },
];

const LINK =
  'group flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-slate-600 transition-colors ' +
  'hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500';
const ACTIVE = '!bg-indigo-50 !text-indigo-700';

/**
 * The sidebar's links: the current workspace's sections, then (admins only) platform administration,
 * visually separated. Used in the desktop sidebar and in the mobile menu.
 */
@Component({
  selector: 'app-nav',
  imports: [RouterLink, RouterLinkActive, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="flex flex-col gap-6" aria-label="Main">
      <div>
        <h2 class="px-3 text-xs font-semibold uppercase tracking-wider text-slate-500">
          Workspace
        </h2>
        @if (context.current(); as ws) {
          <p
            class="mt-1 truncate px-3 text-sm font-medium text-slate-900"
            data-testid="nav-workspace-name"
          >
            {{ ws.name }}
          </p>
          <ul class="mt-2 space-y-0.5" data-testid="workspace-sections">
            @for (s of sections; track s.path) {
              <li>
                <a
                  [routerLink]="s.path ? ['/workspaces', ws.id, s.path] : ['/workspaces', ws.id]"
                  [routerLinkActive]="active"
                  [routerLinkActiveOptions]="{ exact: s.exact }"
                  ariaCurrentWhenActive="page"
                  [class]="link"
                  (click)="navigate.emit()"
                >
                  <app-icon
                    [name]="s.icon"
                    class="size-5 text-slate-400 group-hover:text-slate-500 group-aria-[current=page]:text-indigo-600"
                  />
                  {{ s.label }}
                </a>
              </li>
            }
          </ul>
        } @else {
          <p class="mt-1 px-3 text-sm text-slate-500">No workspace selected.</p>
        }
        <a
          routerLink="/workspaces"
          [routerLinkActive]="active"
          [routerLinkActiveOptions]="{ exact: true }"
          ariaCurrentWhenActive="page"
          [class]="link + ' mt-2'"
          (click)="navigate.emit()"
        >
          <app-icon
            name="folders"
            class="size-5 text-slate-400 group-hover:text-slate-500 group-aria-[current=page]:text-indigo-600"
          />
          All workspaces
        </a>
      </div>

      @if (context.isAdmin()) {
        <div class="border-t border-slate-200 pt-4" data-testid="admin-section">
          <h2 class="px-3 text-xs font-semibold uppercase tracking-wider text-slate-500">
            Platform
          </h2>
          <a
            routerLink="/admin"
            [routerLinkActive]="'!bg-amber-50 !text-amber-800'"
            ariaCurrentWhenActive="page"
            [class]="link + ' mt-2'"
            data-testid="admin-link"
            (click)="navigate.emit()"
          >
            <app-icon name="shield" class="size-5 text-amber-600" />
            Admin
          </a>
        </div>
      }
    </nav>
  `,
})
export class AppNav {
  /** A link was followed (the mobile menu closes). */
  readonly navigate = output<void>();

  protected readonly context = inject(WorkspaceContext);
  protected readonly sections = SECTIONS;
  protected readonly link = LINK;
  protected readonly active = ACTIVE;
}
