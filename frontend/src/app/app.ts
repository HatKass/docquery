import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './auth/auth.service';
import { WorkspaceContext } from './workspaces/workspace-context.service';
import { WorkspaceSwitcher } from './workspaces/workspace-switcher';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, WorkspaceSwitcher],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
})
export class App {
  protected readonly auth = inject(AuthService);
  private readonly context = inject(WorkspaceContext);

  constructor() {
    // Load the workspace list once signed in; forget it on sign-out.
    effect(() => {
      if (this.auth.isAuthenticated()) {
        if (!this.context.loaded()) {
          this.context.load().catch(() => undefined);
        }
      } else {
        this.context.clear();
      }
    });
  }

  protected signOut(): void {
    void this.auth.logout();
  }
}
