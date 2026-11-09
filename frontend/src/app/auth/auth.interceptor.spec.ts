import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { FakeUserManager, fakeUser, provideFakeUserManager } from './testing/fake-user-manager';

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
    expect(backend.expectOne('/api/me').request.headers.get('Authorization')).toBe('Bearer access-token-123');
  });

  it('never sends the token to other URLs', async () => {
    await signIn();
    http.get('/actuator/health').subscribe();
    http.get('https://example.com/api/x').subscribe();
    expect(backend.expectOne('/actuator/health').request.headers.has('Authorization')).toBe(false);
    expect(backend.expectOne('https://example.com/api/x').request.headers.has('Authorization')).toBe(false);
  });

  it('starts a new login when the API answers 401', async () => {
    await signIn();
    http.get('/api/me').subscribe({ error: () => undefined });
    backend.expectOne('/api/me').flush({ detail: 'expired' }, { status: 401, statusText: 'Unauthorized' });
    expect(fake.signinRedirect).toHaveBeenCalled();
  });

  it('does not start a login on 403 or 404', async () => {
    await signIn();
    http.get('/api/workspaces/x').subscribe({ error: () => undefined });
    backend.expectOne('/api/workspaces/x').flush({}, { status: 404, statusText: 'Not Found' });
    expect(fake.signinRedirect).not.toHaveBeenCalled();
  });
});
