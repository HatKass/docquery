import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';
import { FakeUserManager, fakeUser, provideFakeUserManager } from './testing/fake-user-manager';

describe('authGuard', () => {
  let fake: FakeUserManager;

  beforeEach(() => {
    fake = new FakeUserManager();
    TestBed.configureTestingModule({ providers: [provideFakeUserManager(fake)] });
  });

  const run = () =>
    TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/workspaces' } as RouterStateSnapshot),
    );

  it('lets signed-in users through', async () => {
    fake.storedUser = fakeUser();
    await TestBed.inject(AuthService).init();
    expect(run()).toBe(true);
    expect(fake.signinRedirect).not.toHaveBeenCalled();
  });

  it('sends signed-out users to Keycloak and remembers where they were going', () => {
    expect(run()).toBe(false);
    expect(fake.signinRedirect).toHaveBeenCalledWith({ state: { returnUrl: '/workspaces' } });
  });
});
