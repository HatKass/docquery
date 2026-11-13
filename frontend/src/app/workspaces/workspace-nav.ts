import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

/** Tabs shared by the pages of one workspace. */
@Component({
  selector: 'app-workspace-nav',
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="flex gap-1 border-b border-slate-200 text-sm" aria-label="Workspace sections">
      <a
        [routerLink]="['/workspaces', workspaceId()]"
        routerLinkActive="border-indigo-600 text-indigo-700"
        [routerLinkActiveOptions]="{ exact: true }"
        ariaCurrentWhenActive="page"
        class="-mb-px border-b-2 border-transparent px-3 py-2 font-medium text-slate-600 hover:text-slate-900"
        >Documents</a
      >
      <a
        [routerLink]="['/workspaces', workspaceId(), 'chat']"
        routerLinkActive="border-indigo-600 text-indigo-700"
        ariaCurrentWhenActive="page"
        class="-mb-px border-b-2 border-transparent px-3 py-2 font-medium text-slate-600 hover:text-slate-900"
        >Ask</a
      >
      <a
        [routerLink]="['/workspaces', workspaceId(), 'members']"
        routerLinkActive="border-indigo-600 text-indigo-700"
        ariaCurrentWhenActive="page"
        class="-mb-px border-b-2 border-transparent px-3 py-2 font-medium text-slate-600 hover:text-slate-900"
        >Members &amp; settings</a
      >
    </nav>
  `,
})
export class WorkspaceNav {
  readonly workspaceId = input.required<string>();
}
