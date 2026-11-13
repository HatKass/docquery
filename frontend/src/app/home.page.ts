import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from './auth/auth.service';
import { WorkspaceContext } from './workspaces/workspace-context.service';

/** Only app-relative targets: never an open redirect to another site. */
export function safeReturnUrl(url: string | null | undefined): string | null {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/\\') ? url : null;
}

/**
 * Signed out: what DocQuery is and a sign-in button. Arriving here from a protected page (signed out,
 * session expired, or Back after signing out) keeps that page in `?returnUrl=` and returns to it after
 * sign-in. Signed in: jump to the requested page or the current workspace.
 */
@Component({
  selector: 'app-home-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!auth.isAuthenticated()) {
      <section
        class="mx-auto max-w-2xl rounded-xl border border-slate-200 bg-white p-6 text-center shadow-sm sm:p-8"
      >
        @if (target()) {
          <p
            class="mb-5 rounded-md bg-indigo-50 px-3 py-2 text-sm text-indigo-800"
            role="status"
            data-testid="signed-out-notice"
          >
            You are signed out. Sign in to continue where you left off.
          </p>
        }
        <h2 class="text-2xl font-semibold text-slate-900">Ask your documents anything</h2>
        <p class="mt-3 text-slate-600">
          Upload documents into a shared workspace and get answers that cite the exact page they
          came from.
        </p>
        <button
          type="button"
          data-testid="sign-in"
          class="mt-6 rounded-md bg-indigo-600 px-5 py-2.5 font-medium text-white hover:bg-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2"
          (click)="signIn()"
        >
          Sign in
        </button>
        <p class="mt-4 text-xs text-slate-500">
          Demo accounts: owner, member, viewer (password demo123)
        </p>
        <a routerLink="/status" class="mt-2 inline-block text-xs text-indigo-700 hover:underline"
          >System status</a
        >
      </section>
    } @else {
      <p class="text-center text-sm text-slate-500">Opening your workspace…</p>
    }
  `,
})
export class HomePage implements OnInit {
  /** Bound from `?returnUrl=`: the protected page the user was on. */
  readonly returnUrl = input<string | undefined>();

  protected readonly auth = inject(AuthService);
  private readonly context = inject(WorkspaceContext);
  private readonly router = inject(Router);

  protected readonly target = computed(() => safeReturnUrl(this.returnUrl()));

  protected signIn(): void {
    void this.auth.login(this.target() ?? '/');
  }

  async ngOnInit(): Promise<void> {
    if (!this.auth.isAuthenticated()) {
      return;
    }
    const target = this.target();
    if (target) {
      await this.router.navigateByUrl(target, { replaceUrl: true });
      return;
    }
    try {
      await this.context.load();
    } catch {
      // The workspaces page shows the error and lets the user retry.
    }
    const current = this.context.current();
    await this.router.navigate(current ? ['/workspaces', current.id] : ['/workspaces'], {
      replaceUrl: true,
    });
  }
}
