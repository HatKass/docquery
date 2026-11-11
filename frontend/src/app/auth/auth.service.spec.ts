import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
import { FakeUserManager, fakeUser, provideFakeUserManager } from './testing/fake-user-manager';

describe('AuthService', () => {
  let fake: FakeUserManager;
  let auth: AuthService;

  beforeEach(() => {
    fake = new FakeUserManager();
    TestBed.configureTestingModule({ providers: [provideFakeUserManager(fake)] });
    auth = TestBed.inject(AuthService);
  });

  it('starts signed out when nothing is stored', async () => {
    await auth.init();
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.accessToken()).toBeNull();
  });

  it('restores a stored, unexpired session', async () => {
    fake.storedUser = fakeUser();
    await auth.init();
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.accessToken()).toBe('access-token-123');
    expect(auth.displayName()).toBe('Olivia Owner');
  });

  it('ignores an expired stored session', async () => {
    fake.storedUser = fakeUser({ expired: true });
    await auth.init();
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('starts the redirect login with the return URL as state', async () => {
    await auth.login('/workspaces/42');
    expect(fake.signinRedirect).toHaveBeenCalledWith({ state: { returnUrl: '/workspaces/42' } });
  });

  it('completes login and returns the remembered URL', async () => {
    fake.storedUser = fakeUser({ state: { returnUrl: '/workspaces/42' } });
    await expect(auth.completeLogin()).resolves.toBe('/workspaces/42');
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('never redirects to another site after login', async () => {
    fake.storedUser = fakeUser({ state: { returnUrl: '//evil.example/phish' } });
    await expect(auth.completeLogin()).resolves.toBe('/');
    fake.storedUser = fakeUser({ state: { returnUrl: 'https://evil.example' } });
    await expect(auth.completeLogin()).resolves.toBe('/');
  });

  it('signs out when the token expires or silent renew fails', async () => {
    fake.storedUser = fakeUser();
    await auth.init();

    fake.emit('expired');
    expect(auth.isAuthenticated()).toBe(false);

    fake.emit('loaded', fakeUser());
    expect(auth.isAuthenticated()).toBe(true);

    fake.emit('renewError');
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('logs out through Keycloak', async () => {
    await auth.logout();
    expect(fake.signoutRedirect).toHaveBeenCalled();
  });
});
