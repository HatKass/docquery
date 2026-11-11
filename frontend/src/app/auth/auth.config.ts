import { InjectionToken, inject } from '@angular/core';
import { UserManager, UserManagerSettings, WebStorageStateStore } from 'oidc-client-ts';

export interface AuthConfig {
  /** Keycloak realm URL (the OIDC issuer). */
  authority: string;
  clientId: string;
  scope: string;
}

/**
 * Local defaults match compose.yaml and infra/keycloak/realm/docquery-realm.json.
 * (Runtime configuration for other environments arrives with the deployment phase.)
 */
export const DEFAULT_AUTH_CONFIG: AuthConfig = {
  authority: 'http://localhost:8180/realms/docquery',
  clientId: 'docquery-frontend',
  scope: 'openid profile email',
};

export const AUTH_CONFIG = new InjectionToken<AuthConfig>('AUTH_CONFIG', {
  providedIn: 'root',
  factory: () => DEFAULT_AUTH_CONFIG,
});

export function userManagerSettings(config: AuthConfig, origin: string): UserManagerSettings {
  return {
    authority: config.authority,
    client_id: config.clientId,
    scope: config.scope,
    // Authorization code flow; oidc-client-ts always uses PKCE (S256), which the realm enforces.
    response_type: 'code',
    redirect_uri: `${origin}/auth/callback`,
    post_logout_redirect_uri: `${origin}/`,
    // Renews the access token shortly before it expires, using the refresh token.
    automaticSilentRenew: true,
    // Tokens live in sessionStorage: they survive a reload but not closing the tab,
    // and are never sent automatically like cookies (so no CSRF exposure).
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  };
}

/** The oidc-client-ts UserManager, behind a token so tests can replace it with a fake. */
export const USER_MANAGER = new InjectionToken<UserManager>('USER_MANAGER', {
  providedIn: 'root',
  factory: () => new UserManager(userManagerSettings(inject(AUTH_CONFIG), window.location.origin)),
});
