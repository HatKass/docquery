import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ConfirmDialog, ConfirmOptions } from '../shared/confirm-dialog';
import { problemMessage } from '../shared/problem';
import { WorkspaceApi } from './workspace-api.service';
import { WorkspaceContext } from './workspace-context.service';
import { WorkspaceNav } from './workspace-nav';
import { Member, WORKSPACE_ROLES, WorkspaceRole } from './workspace.model';

/** What each role allows, shown when changing someone's role. */
const ROLE_CONSEQUENCES: Record<WorkspaceRole, string> = {
  VIEWER: 'Viewers can read documents but cannot upload, delete or manage members.',
  MEMBER: 'Members can read, upload and delete documents but cannot manage members or workspace settings.',
  OWNER: 'Owners have full control of the workspace.',
};

/**
 * A workspace's "Members & settings" tab: name, the caller's role and the member list. Owners can
 * rename, manage members and delete; everyone can leave. (The workspace home is the document library.)
 */
@Component({
  selector: 'app-workspace-detail-page',
  imports: [WorkspaceNav],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (notFound()) {
      <section class="mx-auto max-w-md rounded-xl border border-slate-200 bg-white p-6 text-sm" data-testid="workspace-not-found">
        <h2 class="font-semibold text-slate-900">Workspace not found</h2>
        <p class="mt-2 text-slate-600">It does not exist or you are not a member.</p>
      </section>
    } @else if (workspace(); as ws) {
      <section class="mx-auto max-w-3xl space-y-6">
        <div class="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
          <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div class="min-w-0">
              <h2 class="break-words text-xl font-semibold text-slate-900" data-testid="workspace-name">{{ ws.name }}</h2>
              <p class="mt-1 text-sm text-slate-500">
                Your role: <span class="font-medium text-slate-700" data-testid="my-role">{{ ws.role }}</span>
              </p>
            </div>
            @if (isOwner()) {
              <form class="flex w-full gap-2 sm:w-auto" (submit)="rename($event)">
                <label for="rename" class="sr-only">New name</label>
                <input
                  id="rename"
                  class="min-w-0 flex-1 rounded-md border border-slate-300 px-2 py-1.5 text-sm sm:w-48 sm:flex-none"
                  maxlength="100"
                  [value]="newName()"
                  (input)="newName.set($any($event.target).value)"
                  placeholder="Rename…"
                />
                <button type="submit" class="shrink-0 rounded-md border border-slate-300 px-3 py-1.5 text-sm" [disabled]="!newName().trim()">
                  Rename
                </button>
              </form>
            }
          </div>
          <div class="mt-4"><app-workspace-nav [workspaceId]="ws.id" /></div>
        </div>

        <div class="rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
          <h3 class="font-semibold text-slate-900">Members</h3>
          <table class="mt-4 w-full text-left text-sm" data-testid="member-table">
            <thead class="text-xs uppercase text-slate-500">
              <tr>
                <th class="py-2">Name</th>
                <th class="hidden sm:table-cell">Username</th>
                <th>Role</th>
                <th><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              @for (m of members(); track m.userId) {
                <tr>
                  <td class="max-w-0 py-2 pr-2 text-slate-800 sm:max-w-none">
                    <span class="block truncate">{{ m.displayName }}</span>
                    <!-- Phones: the username column is folded in here. -->
                    <span class="block truncate text-xs text-slate-500 sm:hidden">{{ m.username }}</span>
                  </td>
                  <td class="hidden text-slate-500 sm:table-cell">{{ m.username }}</td>
                  <td class="w-px pr-2">
                    @if (isOwner()) {
                      <label class="sr-only" [for]="'role-' + m.userId">Role of {{ m.displayName }}</label>
                      <select
                        [id]="'role-' + m.userId"
                        class="rounded border border-slate-300 px-1 py-1 sm:py-0.5"
                        [attr.data-testid]="'role-select-' + m.userId"
                        (change)="changeRole(m, $any($event.target))"
                      >
                        @for (r of roles; track r) {
                          <option [value]="r" [selected]="r === m.role">{{ r }}</option>
                        }
                      </select>
                    } @else {
                      {{ m.role }}
                    }
                  </td>
                  <td class="w-px whitespace-nowrap text-right">
                    @if (m.userId === myId()) {
                      <button
                        type="button"
                        class="text-red-700 hover:underline"
                        data-testid="leave-workspace"
                        [attr.aria-label]="'Leave ' + ws.name"
                        (click)="remove(m)"
                      >
                        Leave
                      </button>
                    } @else if (isOwner()) {
                      <button
                        type="button"
                        class="text-red-700 hover:underline"
                        [attr.data-testid]="'remove-member-' + m.userId"
                        [attr.aria-label]="'Remove ' + m.displayName"
                        (click)="remove(m)"
                      >
                        Remove
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>

          @if (isOwner()) {
            <form class="mt-6 flex flex-wrap gap-2" (submit)="addMember($event)">
              <label for="new-member" class="sr-only">Username or e-mail</label>
              <input
                id="new-member"
                class="w-full min-w-0 rounded-md border border-slate-300 px-3 py-2 text-sm sm:w-auto sm:min-w-48 sm:flex-1"
                placeholder="Username or e-mail"
                [value]="newMember()"
                (input)="newMember.set($any($event.target).value)"
              />
              <label for="new-member-role" class="sr-only">Role</label>
              <select
                id="new-member-role"
                class="rounded-md border border-slate-300 px-2 py-2 text-sm"
                (change)="newMemberRole.set($any($event.target).value)"
              >
                @for (r of roles; track r) {
                  <option [value]="r" [selected]="r === newMemberRole()">{{ r }}</option>
                }
              </select>
              <button
                type="submit"
                class="flex-1 rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50 sm:flex-none"
                [disabled]="!newMember().trim()"
              >
                Add member
              </button>
            </form>
            <p class="mt-2 text-xs text-slate-500">People need to sign in to DocQuery once before they can be added.</p>
          }
        </div>

        @if (isOwner()) {
          <div class="rounded-xl border border-red-200 bg-white p-4 sm:p-6">
            <h3 class="font-semibold text-red-800">Delete workspace</h3>
            <p class="mt-1 text-sm text-slate-600">Removes the workspace for everyone. This cannot be undone.</p>
            <button
              type="button"
              class="mt-3 max-w-full truncate rounded-md bg-red-600 px-4 py-2 text-sm text-white"
              data-testid="delete-workspace"
              (click)="deleteWorkspace()"
            >
              Delete “{{ ws.name }}”
            </button>
          </div>
        }

        @if (error()) {
          <p class="rounded-md bg-red-50 p-3 text-sm text-red-800" role="alert" data-testid="detail-error">{{ error() }}</p>
        }
      </section>
    } @else {
      <p class="text-center text-sm text-slate-500">Loading…</p>
    }
  `,
})
export class WorkspaceDetailPage {
  /** Bound from the `:workspaceId` route parameter. */
  readonly workspaceId = input.required<string>();

  private readonly api = inject(WorkspaceApi);
  private readonly context = inject(WorkspaceContext);
  private readonly router = inject(Router);
  private readonly confirm = inject(ConfirmDialog);

  protected readonly roles = WORKSPACE_ROLES;
  protected readonly members = signal<Member[]>([]);
  protected readonly notFound = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly newName = signal('');
  protected readonly newMember = signal('');
  protected readonly newMemberRole = signal<WorkspaceRole>('MEMBER');

  protected readonly workspace = computed(() => this.context.workspaces().find((w) => w.id === this.workspaceId()) ?? null);
  protected readonly isOwner = computed(() => this.workspace()?.role === 'OWNER');
  protected readonly myId = computed(() => this.context.me()?.id);

  constructor() {
    // Re-open whenever the route parameter changes (e.g. via the workspace switcher).
    effect(() => {
      const id = this.workspaceId();
      untracked(() => void this.open(id));
    });
  }

  private async open(id: string): Promise<void> {
    this.notFound.set(false);
    this.error.set(null);
    try {
      if (!this.context.loaded()) {
        await this.context.load();
      }
      if (!this.context.workspaces().some((w) => w.id === id)) {
        this.notFound.set(true);
        return;
      }
      this.context.select(id);
      await this.reloadMembers();
    } catch (e) {
      this.error.set(problemMessage(e));
    }
  }

  private async reloadMembers(): Promise<void> {
    this.members.set(await firstValueFrom(this.api.members(this.workspaceId())));
  }

  /** Runs a change, then refreshes members and workspace list; shows the API's message on failure. */
  private async run(action: () => Promise<unknown>): Promise<boolean> {
    this.error.set(null);
    try {
      await action();
      await this.context.load();
      if (this.context.workspaces().some((w) => w.id === this.workspaceId())) {
        await this.reloadMembers();
      }
      return true;
    } catch (e) {
      this.error.set(problemMessage(e));
      await this.reloadMembers().catch(() => undefined);
      return false;
    }
  }

  protected async rename(event: Event): Promise<void> {
    event.preventDefault();
    if (await this.run(() => firstValueFrom(this.api.rename(this.workspaceId(), this.newName().trim())))) {
      this.newName.set('');
    }
  }

  protected async addMember(event: Event): Promise<void> {
    event.preventDefault();
    const ok = await this.run(() =>
      firstValueFrom(this.api.addMember(this.workspaceId(), this.newMember().trim(), this.newMemberRole())),
    );
    if (ok) {
      this.newMember.set('');
    }
  }

  /**
   * Every role change is confirmed. Promoting to OWNER and changing your own role are risky, so they
   * use the danger variant. On cancel (or a rejected change the user gives up on) the dropdown goes
   * back to the stored role: Angular will not re-apply [selected] because the bound value did not change.
   */
  protected async changeRole(member: Member, select: HTMLSelectElement): Promise<void> {
    const role = select.value as WorkspaceRole;
    if (role === member.role) {
      return;
    }
    const changed = await this.confirmChange(this.roleChangeDialog(member, role), () =>
      firstValueFrom(this.api.changeRole(this.workspaceId(), member.userId, role)),
    );
    if (!changed) {
      select.value = member.role;
    }
  }

  private roleChangeDialog(member: Member, role: WorkspaceRole): ConfirmOptions {
    const self = member.userId === this.myId();
    const workspace = this.workspace()?.name ?? 'this workspace';
    const parts = [ROLE_CONSEQUENCES[role]];
    if (role === 'OWNER') {
      parts.push(
        `${self ? 'You' : member.displayName} will be able to manage members, remove other owners (including you) ` +
          `and delete ${workspace} with all its documents.`,
      );
    }
    if (self) {
      parts.push(
        'You are changing your own role: you may immediately lose access to members and settings, ' +
          'and only another owner can give it back.',
      );
    }
    const danger = self || role === 'OWNER';
    return {
      title: self
        ? `Change your own role from ${member.role} to ${role}?`
        : `Change ${member.displayName}'s role from ${member.role} to ${role}?`,
      message: parts.join(' '),
      variant: danger ? 'danger' : 'default',
      confirmLabel: role === 'OWNER' && !self ? 'Make owner' : self ? 'Change my role' : 'Change role',
      busyLabel: 'Saving…',
    };
  }

  protected async remove(member: Member): Promise<void> {
    const leaving = member.userId === this.myId();
    const workspace = this.workspace()?.name ?? 'this workspace';
    const options: ConfirmOptions = leaving
      ? {
          title: `Leave ${workspace}?`,
          message:
            'You will immediately lose access to its documents. Documents you uploaded stay in the workspace. ' +
            'Only an owner can add you again.',
          variant: 'danger',
          confirmLabel: 'Leave workspace',
          busyLabel: 'Leaving…',
        }
      : {
          title: `Remove ${member.displayName} from ${workspace}?`,
          message:
            `${member.displayName} will immediately lose access to its documents. ` +
            'Documents they uploaded stay in the workspace.',
          variant: 'danger',
          confirmLabel: 'Remove member',
          busyLabel: 'Removing…',
        };
    const removed = await this.confirmChange(options, () =>
      firstValueFrom(this.api.removeMember(this.workspaceId(), member.userId)),
    );
    if (removed && leaving) {
      await this.router.navigate(['/workspaces']);
    }
  }

  protected async deleteWorkspace(): Promise<void> {
    const ws = this.workspace();
    if (!ws) {
      return;
    }
    const deleted = await this.confirmChange(
      {
        title: `Delete ${ws.name}?`,
        message:
          'The workspace, all its documents with their text chunks and embeddings, and its member list will be ' +
          'permanently removed for everyone. This cannot be undone.',
        variant: 'danger',
        confirmLabel: 'Delete workspace',
        busyLabel: 'Deleting…',
      },
      () => firstValueFrom(this.api.delete(ws.id)),
    );
    if (deleted) {
      await this.router.navigate(['/workspaces']);
    }
  }

  /**
   * Confirms, runs the request inside the dialog (busy state, errors shown there), then refreshes
   * the workspace list and members. @returns whether the change was made
   */
  private async confirmChange(options: ConfirmOptions, request: () => Promise<unknown>): Promise<boolean> {
    this.error.set(null);
    const done = await this.confirm.open({ ...options, action: request });
    if (done) {
      await this.refreshAfterChange();
    }
    return done;
  }

  private async refreshAfterChange(): Promise<void> {
    try {
      await this.context.load();
      if (this.context.workspaces().some((w) => w.id === this.workspaceId())) {
        await this.reloadMembers();
      }
    } catch (e) {
      this.error.set(problemMessage(e));
    }
  }
}
