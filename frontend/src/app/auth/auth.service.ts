import { Injectable, computed, inject, signal } from '@angular/core';
import { User } from 'oidc-client-ts';
import { USER_MANAGER } from './auth.config';

interface LoginState {
  returnUrl: string;
}

/**
 * Signal-based facade over oidc-client-ts: OIDC authorization code flow with PKCE against Keycloak.
 * The SPA never sees a password; it only holds short-lived access tokens for the API.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly userManager = inject(USER_MANAGER);

  private readonly _user = signal<User | null>(null);
  private readonly _signingOut = signal(false);
  /** Between "Sign out" and leaving the page: failing requests are expected then, not an expiry. */
  readonly isSigningOut = this._signingOut.asReadonly();
  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => {
    const user = this._user();
    return !!user && !user.expired;
  });
  readonly displayName = computed(() => {
    const profile = this._user()?.profile;
    return profile?.name ?? profile?.preferred_username ?? '';
  });

  constructor() {
    this.userManager.events.addUserLoaded((user) => this._user.set(user));
    this.userManager.events.addUserUnloaded(() => this._user.set(null));
    this.userManager.events.addAccessTokenExpired(() => this._user.set(null));
    this.userManager.events.addSilentRenewError(() => this._user.set(null));
  }

  /** Restores a session from sessionStorage (runs once at startup). */
  async init(): Promise<void> {
    const user = await this.userManager.getUser();
    this._user.set(user && !user.expired ? user : null);
  }

  accessToken(): string | null {
    return this.isAuthenticated() ? (this._user()?.access_token ?? null) : null;
  }

  /** Redirects to the Keycloak login page; afterwards the user returns to `returnUrl`. */
  login(returnUrl = '/'): Promise<void> {
    return this.userManager.signinRedirect({ state: { returnUrl } satisfies LoginState });
  }

  /** Handles the redirect back from Keycloak and returns where the user wanted to go. */
  async completeLogin(): Promise<string> {
    const user = await this.userManager.signinRedirectCallback();
    this._user.set(user);
    const returnUrl = (user.state as LoginState | undefined)?.returnUrl;
    // Only allow app-relative targets, never an open redirect to another site.
    return returnUrl && returnUrl.startsWith('/') && !returnUrl.startsWith('//') ? returnUrl : '/';
  }

  /** Ends the Keycloak session too (RP-initiated logout), not just the local one. */
  async logout(): Promise<void> {
    this._signingOut.set(true);
    try {
      await this.userManager.signoutRedirect();
    } catch (e) {
      this._signingOut.set(false);
      throw e;
    }
  }

  /** The API no longer accepts the token: forget the local session (Keycloak's may live on). */
  async clearSession(): Promise<void> {
    this._user.set(null);
    await this.userManager.removeUser();
  }

  /**
   * Re-reads the session after the page was restored from the back-forward cache, where in-memory
   * state (including "signing out") is a snapshot from before the user left.
   */
  async revalidate(): Promise<void> {
    this._signingOut.set(false);
    await this.init();
  }
}
