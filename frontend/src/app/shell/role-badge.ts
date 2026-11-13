import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { WorkspaceRole } from '../workspaces/workspace.model';

const STYLES: Record<WorkspaceRole, string> = {
  OWNER: 'bg-indigo-50 text-indigo-700 ring-indigo-200',
  MEMBER: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  VIEWER: 'bg-slate-100 text-slate-600 ring-slate-200',
};

/** The caller's role in a workspace, as a small pill. The text carries the meaning; color only helps. */
@Component({
  selector: 'app-role-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span
    class="inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-semibold tracking-wide ring-1 ring-inset"
    [class]="style()"
    data-testid="role-badge"
    >{{ role() }}</span
  >`,
})
export class RoleBadge {
  readonly role = input.required<WorkspaceRole>();
  protected readonly style = computed(() => STYLES[this.role()]);
}
