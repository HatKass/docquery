import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { FakeUserManager, fakeUser, provideFakeUserManager } from './testing/fake-user-manager';

const settle = () => new Promise((r) => setTimeout(r));

describe('authInterceptor', () => {
  let fake: FakeUserManager;
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(async () => {
    fake = new FakeUserManager();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideFakeUserManager(fake),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  async function signIn(): Promise<void> {
    fake.storedUser = fakeUser();
    await TestBed.inject(AuthService).init();
  }

  it('adds the bearer token to API calls', async () => {
    await signIn();
    http.get('/api/me').subscribe();
    expect(backend.expectOne('/api/me').request.headers.get('Authorization')).toBe(
      'Bearer access-token-123',
    );
  });

  it('never sends the token to other URLs', async () => {
    await signIn();
    http.get('/actuator/health').subscribe();
    http.get('https://example.com/api/x').subscribe();
    expect(backend.expectOne('/actuator/health').request.headers.has('Authorization')).toBe(false);
    expect(
      backend.expectOne('https://example.com/api/x').request.headers.has('Authorization'),
    ).toBe(false);
  });

  it('on 401 clears the local session and goes to the signed-out home, remembering the page', async () => {
    await signIn();
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/admin');
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    http.get('/api/admin/overview').subscribe({ error: () => undefined });
    backend
      .expectOne('/api/admin/overview')
      .flush({ detail: 'expired' }, { status: 401, statusText: 'Unauthorized' });
    await settle();

    expect(fake.removeUser).toHaveBeenCalled();
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
    expect(router.serializeUrl(navigate.mock.calls[0][0] as UrlTree)).toBe('/?returnUrl=%2Fadmin');
    // The sign-in page is a click away, not an automatic redirect to Keycloak.
    expect(fake.signinRedirect).not.toHaveBeenCalled();
  });

  it('handles several concurrent 401s once', async () => {
    await signIn();
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/admin');
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    http.get('/api/admin/overview').subscribe({ error: () => undefined });
    http.get('/api/admin/usage').subscribe({ error: () => undefined });
    backend.expectOne('/api/admin/overview').flush({}, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/admin/usage').flush({}, { status: 401, statusText: 'Unauthorized' });
    await settle();

    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('ignores 401s while signing out (requests in flight are expected to fail)', async () => {
    await signIn();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fake.signoutRedirect.mockImplementation(() => new Promise(() => undefined)); // leaving the page
    void TestBed.inject(AuthService).logout();

    http.get('/api/admin/overview').subscribe({ error: () => undefined });
    backend.expectOne('/api/admin/overview').flush({}, { status: 401, statusText: 'Unauthorized' });
    await settle();

    expect(fake.removeUser).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('does not start a login on 403 or 404', async () => {
    await signIn();
    http.get('/api/workspaces/x').subscribe({ error: () => undefined });
    backend.expectOne('/api/workspaces/x').flush({}, { status: 404, statusText: 'Not Found' });
    expect(fake.signinRedirect).not.toHaveBeenCalled();
    expect(fake.removeUser).not.toHaveBeenCalled();
  });
});
