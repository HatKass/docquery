import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AuthService } from './auth/auth.service';
import { FakeUserManager, fakeUser, provideFakeUserManager } from './auth/testing/fake-user-manager';

describe('App', () => {
  let fake: FakeUserManager;

  beforeEach(async () => {
    fake = new FakeUserManager();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideFakeUserManager(fake)],
    }).compileComponents();
  });

  it('renders the DocQuery header', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent).toContain('DocQuery');
    expect(compiled.querySelector('[data-testid="sign-out"]')).toBeNull();
  });

  it('shows the user, the workspace switcher and sign-out once signed in', async () => {
    fake.storedUser = fakeUser();
    await TestBed.inject(AuthService).init();

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/me')
      .flush({
        id: 'u1',
        username: 'owner',
        email: null,
        displayName: 'Olivia Owner',
        workspaces: [{ id: 'demo', name: 'Demo workspace', role: 'OWNER', memberCount: 3, createdAt: '' }],
      });
    // Let the load() promise resolve, then render the updated signals.
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="user-name"]')?.textContent).toContain('Olivia Owner');
    expect(el.querySelector('[data-testid="workspace-switcher"]')?.textContent).toContain('Demo workspace');

    (el.querySelector('[data-testid="sign-out"]') as HTMLButtonElement).click();
    expect(fake.signoutRedirect).toHaveBeenCalled();
  });
});
