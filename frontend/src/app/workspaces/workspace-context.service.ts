import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { WorkspaceApi } from './workspace-api.service';
import { Me, Workspace } from './workspace.model';

const STORAGE_KEY = 'docquery.currentWorkspace';

/**
 * The signed-in user's workspaces and the one currently selected in the switcher.
 * The selection is only a UI preference (remembered per browser); the API checks access on every
 * request, so a stale or tampered value can never reveal another tenant's data.
 */
@Injectable({ providedIn: 'root' })
export class WorkspaceContext {
  private readonly api = inject(WorkspaceApi);

  private readonly _me = signal<Me | null>(null);
  private readonly _currentId = signal<string | null>(readStoredId());

  readonly me = this._me.asReadonly();
  readonly workspaces = computed<Workspace[]>(() => this._me()?.workspaces ?? []);
  readonly current = computed<Workspace | null>(() => {
    const all = this.workspaces();
    return all.find((w) => w.id === this._currentId()) ?? all[0] ?? null;
  });
  readonly loaded = computed(() => this._me() !== null);
  /** UI hint only: every admin endpoint is protected by the API itself. */
  readonly isAdmin = computed(() => this._me()?.admin === true);

  private inFlight: Promise<void> | null = null;

  /** (Re)loads the profile and workspace list from the API; concurrent calls share one request. */
  load(): Promise<void> {
    this.inFlight ??= firstValueFrom(this.api.me())
      .then((me) => this._me.set(me))
      .finally(() => (this.inFlight = null));
    return this.inFlight;
  }

  select(workspaceId: string): void {
    this._currentId.set(workspaceId);
    try {
      localStorage.setItem(STORAGE_KEY, workspaceId);
    } catch {
      // Storage can be unavailable (private mode); the selection then lasts for this tab only.
    }
  }

  async create(name: string): Promise<Workspace> {
    const created = await firstValueFrom(this.api.create(name));
    await this.load();
    this.select(created.id);
    return created;
  }

  clear(): void {
    this._me.set(null);
  }
}

function readStoredId(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}
