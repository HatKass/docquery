import { Injectable, inject } from '@angular/core';
import { Router, UrlTree } from '@angular/router';
import { AuthService } from './auth.service';

/** First path segments reachable without signing in: home, system status and the OIDC callback. */
const PUBLIC_SEGMENTS = new Set(['', 'status', 'auth']);

/**
 * The one place that decides what happens when the session is gone: the API answered 401, the token
 * expired, or a page was restored from the browser's back-forward cache after signing out.
 * The user always lands on the signed-out home page, which remembers where they were going
 * ({@code ?returnUrl=...}) so signing in takes them back there.
 */
@Injectable({ providedIn: 'root' })
export class SessionWatch {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  private clearing = false;

  /** Whether a URL needs a signed-in user (everything except home, status and the callback). */
  isProtected(url: string): boolean {
    const first = this.router.parseUrl(url).root.children['primary']?.segments[0]?.path ?? '';
    return !PUBLIC_SEGMENTS.has(first);
  }

  /** The signed-out home page, remembering a protected `returnUrl`. */
  signedOutTree(returnUrl: string): UrlTree {
    return this.router.createUrlTree(['/'], {
      queryParams: this.isProtected(returnUrl) ? { returnUrl } : {},
    });
  }

  /**
   * The API rejected the token (401). Forgets the local session and leaves the protected page.
   * Ignored while signing out (requests still in flight are expected to fail then); concurrent 401s
   * are handled once.
   */
  async onUnauthorized(): Promise<void> {
    if (this.auth.isSigningOut() || this.clearing) {
      return;
    }
    this.clearing = true;
    const url = this.router.url;
    try {
      await this.auth.clearSession();
      await this.leaveIfSignedOut(url);
    } finally {
      this.clearing = false;
    }
  }

  /**
   * The browser restored this page from its back-forward cache: the app's in-memory state (and the
   * guards' last decision) is from before. Re-read the session and re-check the current URL.
   */
  async onPageRestored(): Promise<void> {
    await this.auth.revalidate();
    await this.leaveIfSignedOut(this.router.url);
  }

  /** Leaves a protected page when nobody is signed in (no-op while signing out). */
  async leaveIfSignedOut(url = this.router.url): Promise<void> {
    if (this.auth.isAuthenticated() || this.auth.isSigningOut() || !this.isProtected(url)) {
      return;
    }
    await this.router.navigateByUrl(this.signedOutTree(url), { replaceUrl: true });
  }
}
