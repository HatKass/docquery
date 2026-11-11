import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { problemMessage } from '../shared/problem';
import { WorkspaceApi } from './workspace-api.service';
import { WorkspaceContext } from './workspace-context.service';
import { WorkspaceNav } from './workspace-nav';
import { Member, WORKSPACE_ROLES, WorkspaceRole } from './workspace.model';

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
        <div class="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <div class="flex items-start justify-between gap-4">
            <div>
              <h2 class="text-xl font-semibold text-slate-900" data-testid="workspace-name">{{ ws.name }}</h2>
              <p class="mt-1 text-sm text-slate-500">
                Your role: <span class="font-medium text-slate-700" data-testid="my-role">{{ ws.role }}</span>
              </p>
            </div>
            @if (isOwner()) {
              <form class="flex gap-2" (submit)="rename($event)">
                <label for="rename" class="sr-only">New name</label>
                <input
                  id="rename"
                  class="rounded-md border border-slate-300 px-2 py-1.5 text-sm"
                  maxlength="100"
                  [value]="newName()"
                  (input)="newName.set($any($event.target).value)"
                  placeholder="Rename…"
                />
                <button type="submit" class="rounded-md border border-slate-300 px-3 py-1.5 text-sm" [disabled]="!newName().trim()">
                  Rename
                </button>
              </form>
            }
          </div>
          <div class="mt-4"><app-workspace-nav [workspaceId]="ws.id" /></div>
        </div>

        <div class="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h3 class="font-semibold text-slate-900">Members</h3>
          <table class="mt-4 w-full text-left text-sm" data-testid="member-table">
            <thead class="text-xs uppercase text-slate-500">
              <tr><th class="py-2">Name</th><th>Username</th><th>Role</th><th></th></tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              @for (m of members(); track m.userId) {
                <tr>
                  <td class="py-2 text-slate-800">{{ m.displayName }}</td>
                  <td class="text-slate-500">{{ m.username }}</td>
                  <td>
                    @if (isOwner()) {
                      <label class="sr-only" [for]="'role-' + m.userId">Role of {{ m.displayName }}</label>
                      <select
                        [id]="'role-' + m.userId"
                        class="rounded border border-slate-300 px-1 py-0.5"
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
                  <td class="text-right">
                    @if (m.userId === myId()) {
                      <button type="button" class="text-red-700 hover:underline" (click)="remove(m)">Leave</button>
                    } @else if (isOwner()) {
                      <button type="button" class="text-red-700 hover:underline" (click)="remove(m)">Remove</button>
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
                class="min-w-48 flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm"
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
                class="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                [disabled]="!newMember().trim()"
              >
                Add member
              </button>
            </form>
            <p class="mt-2 text-xs text-slate-500">People need to sign in to DocQuery once before they can be added.</p>
          }
        </div>

        @if (isOwner()) {
          <div class="rounded-xl border border-red-200 bg-white p-6">
            <h3 class="font-semibold text-red-800">Delete workspace</h3>
            <p class="mt-1 text-sm text-slate-600">Removes the workspace for everyone. This cannot be undone.</p>
            <button type="button" class="mt-3 rounded-md bg-red-600 px-4 py-2 text-sm text-white" (click)="deleteWorkspace()">
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

  protected async changeRole(member: Member, select: HTMLSelectElement): Promise<void> {
    const role = select.value as WorkspaceRole;
    const ok = await this.run(() => firstValueFrom(this.api.changeRole(this.workspaceId(), member.userId, role)));
    if (!ok) {
      // The API rejected the change (e.g. last owner): show the role that is actually stored again.
      // Angular won't re-apply [selected] because the bound value did not change.
      select.value = member.role;
    }
  }

  protected async remove(member: Member): Promise<void> {
    const leaving = member.userId === this.myId();
    const question = leaving ? 'Leave this workspace?' : `Remove ${member.displayName} from this workspace?`;
    if (!confirm(question)) {
      return;
    }
    if ((await this.run(() => firstValueFrom(this.api.removeMember(this.workspaceId(), member.userId)))) && leaving) {
      await this.router.navigate(['/workspaces']);
    }
  }

  protected async deleteWorkspace(): Promise<void> {
    const ws = this.workspace();
    if (!ws || !confirm(`Delete “${ws.name}” for all members? This cannot be undone.`)) {
      return;
    }
    if (await this.run(() => firstValueFrom(this.api.delete(ws.id)))) {
      await this.router.navigate(['/workspaces']);
    }
  }
}
