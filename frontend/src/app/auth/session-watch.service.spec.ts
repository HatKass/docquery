import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from './auth.service';
import { SessionWatch } from './session-watch.service';
import { FakeUserManager, fakeUser, provideFakeUserManager } from './testing/fake-user-manager';

describe('SessionWatch', () => {
  let fake: FakeUserManager;
  let session: SessionWatch;
  let router: Router;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(async () => {
    fake = new FakeUserManager();
    fake.storedUser = fakeUser();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideFakeUserManager(fake)],
    });
    await TestBed.inject(AuthService).init();
    session = TestBed.inject(SessionWatch);
    router = TestBed.inject(Router);
    navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  const target = () => router.serializeUrl(navigate.mock.calls[0][0] as UrlTree);

  it('knows which pages need a signed-in user', () => {
    expect(session.isProtected('/')).toBe(false);
    expect(session.isProtected('/?returnUrl=%2Fadmin')).toBe(false);
    expect(session.isProtected('/status')).toBe(false);
    expect(session.isProtected('/auth/callback?code=x')).toBe(false);
    expect(session.isProtected('/admin')).toBe(true);
    expect(session.isProtected('/workspaces/w1/chat')).toBe(true);
  });

  describe('page restored from the back-forward cache', () => {
    it('after signing out: re-reads the session and leaves the protected page', async () => {
      vi.spyOn(router, 'url', 'get').mockReturnValue('/admin');
      // The frozen page still thinks it is signing out; the session was removed before leaving.
      fake.signoutRedirect.mockImplementation(async () => {
        fake.storedUser = null;
        await new Promise(() => undefined);
      });
      void TestBed.inject(AuthService).logout();
      expect(TestBed.inject(AuthService).isSigningOut()).toBe(true);

      await session.onPageRestored();

      expect(TestBed.inject(AuthService).isSigningOut()).toBe(false);
      expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
      expect(target()).toBe('/?returnUrl=%2Fadmin');
      expect(navigate.mock.calls[0][1]).toEqual({ replaceUrl: true });
    });

    it('still signed in: stays', async () => {
      vi.spyOn(router, 'url', 'get').mockReturnValue('/admin');
      await session.onPageRestored();
      expect(navigate).not.toHaveBeenCalled();
    });

    it('on a public page: stays', async () => {
      fake.storedUser = null;
      vi.spyOn(router, 'url', 'get').mockReturnValue('/status');
      await session.onPageRestored();
      expect(navigate).not.toHaveBeenCalled();
    });
  });
});
