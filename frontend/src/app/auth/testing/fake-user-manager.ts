import { Provider } from '@angular/core';
import { User, UserManager } from 'oidc-client-ts';
import { vi } from 'vitest';
import { USER_MANAGER } from '../auth.config';

type Listener = (user?: User) => void;

/** In-memory stand-in for oidc-client-ts's UserManager, with the event hooks AuthService uses. */
export class FakeUserManager {
  storedUser: User | null = null;
  private readonly listeners: Record<string, Listener[]> = {};

  readonly signinRedirect = vi.fn(async (_args?: unknown) => undefined);
  readonly signoutRedirect = vi.fn(async () => undefined);
  readonly signinRedirectCallback = vi.fn(async () => this.storedUser as User);
  readonly getUser = vi.fn(async () => this.storedUser);

  readonly events = {
    addUserLoaded: (cb: Listener) => this.on('loaded', cb),
    addUserUnloaded: (cb: Listener) => this.on('unloaded', cb),
    addAccessTokenExpired: (cb: Listener) => this.on('expired', cb),
    addSilentRenewError: (cb: Listener) => this.on('renewError', cb),
  };

  emit(event: 'loaded' | 'unloaded' | 'expired' | 'renewError', user?: User): void {
    (this.listeners[event] ?? []).forEach((cb) => cb(user));
  }

  private on(event: string, cb: Listener): void {
    (this.listeners[event] ??= []).push(cb);
  }
}

export function fakeUser(overrides: Partial<{ expired: boolean; name: string; state: unknown }> = {}): User {
  return {
    access_token: 'access-token-123',
    expired: overrides.expired ?? false,
    profile: { sub: 'sub-1', name: overrides.name ?? 'Olivia Owner', preferred_username: 'owner' },
    state: overrides.state,
  } as unknown as User;
}

export function provideFakeUserManager(fake: FakeUserManager): Provider {
  return { provide: USER_MANAGER, useValue: fake as unknown as UserManager };
}
