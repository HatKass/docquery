import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from './auth.service';

/** Landing route for the redirect back from Keycloak (`redirect_uri`). */
@Component({
  selector: 'app-auth-callback',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error()) {
      <section class="mx-auto max-w-md rounded-xl border border-red-200 bg-white p-6 text-sm" data-testid="callback-error">
        <h2 class="font-semibold text-red-800">Sign-in failed</h2>
        <p class="mt-2 text-slate-700">{{ error() }}</p>
        <a routerLink="/" class="mt-4 inline-block text-indigo-700 underline">Back to start</a>
      </section>
    } @else {
      <p class="text-center text-sm text-slate-500">Signing you in…</p>
    }
  `,
})
export class CallbackPage implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      const returnUrl = await this.auth.completeLogin();
      await this.router.navigateByUrl(returnUrl, { replaceUrl: true });
    } catch (e) {
      this.error.set(e instanceof Error ? e.message : 'Unexpected error while completing sign-in.');
    }
  }
}
