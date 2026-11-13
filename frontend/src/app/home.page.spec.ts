import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from './auth/auth.service';
import {
  FakeUserManager,
  fakeUser,
  provideFakeUserManager,
} from './auth/testing/fake-user-manager';
import { HomePage, safeReturnUrl } from './home.page';

describe('HomePage', () => {
  let fake: FakeUserManager;

  beforeEach(() => {
    fake = new FakeUserManager();
    TestBed.configureTestingModule({
      imports: [HomePage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideFakeUserManager(fake),
      ],
    });
  });

  async function render(returnUrl?: string) {
    const fixture = TestBed.createComponent(HomePage);
    if (returnUrl !== undefined) {
      fixture.componentRef.setInput('returnUrl', returnUrl);
    }
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('signed out from a protected page: says so and signs in back to that page', async () => {
    const el = await render('/admin');
    expect(el.querySelector('[data-testid="signed-out-notice"]')?.textContent).toContain(
      'You are signed out',
    );

    el.querySelector<HTMLButtonElement>('[data-testid="sign-in"]')!.click();

    expect(fake.signinRedirect).toHaveBeenCalledWith({ state: { returnUrl: '/admin' } });
  });

  it('a plain visit shows no notice and signs in to the start page', async () => {
    const el = await render();
    expect(el.querySelector('[data-testid="signed-out-notice"]')).toBeNull();
    el.querySelector<HTMLButtonElement>('[data-testid="sign-in"]')!.click();
    expect(fake.signinRedirect).toHaveBeenCalledWith({ state: { returnUrl: '/' } });
  });

  it('never returns to another site', async () => {
    const el = await render('//evil.example/phish');
    expect(el.querySelector('[data-testid="signed-out-notice"]')).toBeNull();
    el.querySelector<HTMLButtonElement>('[data-testid="sign-in"]')!.click();
    expect(fake.signinRedirect).toHaveBeenCalledWith({ state: { returnUrl: '/' } });
    expect(safeReturnUrl('https://evil.example')).toBeNull();
    expect(safeReturnUrl('/\\evil.example')).toBeNull();
    expect(safeReturnUrl('/workspaces/w1')).toBe('/workspaces/w1');
  });

  it('already signed in: goes straight to the requested page', async () => {
    fake.storedUser = fakeUser();
    await TestBed.inject(AuthService).init();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    await render('/admin');

    expect(navigate).toHaveBeenCalledWith('/admin', { replaceUrl: true });
  });
});
