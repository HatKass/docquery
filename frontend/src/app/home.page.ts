import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from './auth/auth.service';
import { WorkspaceContext } from './workspaces/workspace-context.service';

/** Signed out: what DocQuery is and a sign-in button. Signed in: jump to the current workspace. */
@Component({
  selector: 'app-home-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!auth.isAuthenticated()) {
      <section class="mx-auto max-w-2xl rounded-xl border border-slate-200 bg-white p-6 text-center shadow-sm sm:p-8">
        <h2 class="text-2xl font-semibold text-slate-900">Ask your documents anything</h2>
        <p class="mt-3 text-slate-600">
          Upload documents into a shared workspace and get answers that cite the exact page they came from.
        </p>
        <button
          type="button"
          data-testid="sign-in"
          class="mt-6 rounded-md bg-indigo-600 px-5 py-2.5 font-medium text-white hover:bg-indigo-700"
          (click)="auth.login('/')"
        >
          Sign in
        </button>
        <p class="mt-4 text-xs text-slate-500">Demo accounts: owner, member, viewer (password demo123)</p>
        <a routerLink="/status" class="mt-2 inline-block text-xs text-indigo-700 hover:underline">System status</a>
      </section>
    } @else {
      <p class="text-center text-sm text-slate-500">Opening your workspace…</p>
    }
  `,
})
export class HomePage implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly context = inject(WorkspaceContext);
  private readonly router = inject(Router);

  async ngOnInit(): Promise<void> {
    if (!this.auth.isAuthenticated()) {
      return;
    }
    try {
      await this.context.load();
    } catch {
      // The workspaces page shows the error and lets the user retry.
    }
    const current = this.context.current();
    await this.router.navigate(current ? ['/workspaces', current.id] : ['/workspaces'], { replaceUrl: true });
  }
}
