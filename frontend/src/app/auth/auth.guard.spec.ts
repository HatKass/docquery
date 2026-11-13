import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';
import { FakeUserManager, fakeUser, provideFakeUserManager } from './testing/fake-user-manager';

describe('authGuard', () => {
  let fake: FakeUserManager;

  beforeEach(() => {
    fake = new FakeUserManager();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideFakeUserManager(fake)],
    });
  });

  const run = (url = '/workspaces') =>
    TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );

  it('lets signed-in users through', async () => {
    fake.storedUser = fakeUser();
    await TestBed.inject(AuthService).init();
    expect(run()).toBe(true);
  });

  it('sends signed-out users to the home page and remembers where they were going', () => {
    const result = run('/workspaces/w1/chat?conversation=c1') as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(result)).toBe(
      '/?returnUrl=%2Fworkspaces%2Fw1%2Fchat%3Fconversation%3Dc1',
    );
    expect(fake.signinRedirect).not.toHaveBeenCalled();
  });
});
