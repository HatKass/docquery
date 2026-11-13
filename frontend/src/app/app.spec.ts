import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AuthService } from './auth/auth.service';
import {
  FakeUserManager,
  fakeUser,
  provideFakeUserManager,
} from './auth/testing/fake-user-manager';

/** Any URL renders nothing, so links can be followed. */
@Component({ template: '' })
class Blank {}

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('App shell', () => {
  let fake: FakeUserManager;

  beforeEach(async () => {
    fake = new FakeUserManager();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([{ path: '**', component: Blank }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideFakeUserManager(fake),
      ],
    }).compileComponents();
  });

  async function signedIn(admin = false) {
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
        admin,
        workspaces: [
          { id: 'demo', name: 'Demo workspace', role: 'OWNER', memberCount: 3, createdAt: '' },
        ],
      });
    await settle();
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('shows only the wordmark when signed out', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('DocQuery');
    expect(el.querySelector('[data-testid="user-menu-button"]')).toBeNull();
    expect(el.querySelector('[data-testid="sidebar"]')).toBeNull();
    expect(el.querySelector('[data-testid="menu-button"]')).toBeNull();
  });

  it('shows the switcher with the role badge, the user menu and the workspace sections once signed in', async () => {
    const { el } = await signedIn();
    expect(el.querySelector('[data-testid="workspace-switcher"]')?.textContent).toContain(
      'Demo workspace',
    );
    expect(el.querySelector('[data-testid="role-badge"]')?.textContent).toContain('OWNER');
    expect(el.querySelector('[data-testid="user-name"]')?.textContent).toContain('Olivia Owner');
    expect(el.querySelector('[data-testid="avatar"]')?.textContent).toContain('OO');
    const sections = Array.from(
      el.querySelectorAll('[data-testid="sidebar"] [data-testid="workspace-sections"] a'),
    ).map((a) => a.textContent?.trim());
    expect(sections).toEqual(['Documents', 'Ask', 'Members & settings']);
  });

  it('never shows the admin entry to non-admins', async () => {
    const { el } = await signedIn(false);
    expect(el.querySelector('[data-testid="admin-link"]')).toBeNull();
    expect(el.querySelector('[data-testid="admin-section"]')).toBeNull();
  });

  it('shows admins a separate Platform section with the Admin link', async () => {
    const { el } = await signedIn(true);
    const section = el.querySelector('[data-testid="sidebar"] [data-testid="admin-section"]')!;
    expect(section.textContent).toContain('Platform');
    expect(section.querySelector('[data-testid="admin-link"]')?.getAttribute('href')).toBe(
      '/admin',
    );
  });

  it('signs out from the user menu', async () => {
    const { fixture, el } = await signedIn();
    (el.querySelector('[data-testid="user-menu-button"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    (el.querySelector('[data-testid="sign-out"]') as HTMLButtonElement).click();
    expect(fake.signoutRedirect).toHaveBeenCalled();
  });

  describe('mobile navigation', () => {
    it('opens a modal drawer with focus inside, and Escape closes it and returns focus', async () => {
      const { fixture, el } = await signedIn(true);
      const menuButton = el.querySelector<HTMLButtonElement>('[data-testid="menu-button"]')!;
      expect(menuButton.getAttribute('aria-expanded')).toBe('false');

      menuButton.click();
      await fixture.whenStable();

      const drawer = el.querySelector('[data-testid="mobile-nav"]')!;
      expect(drawer.getAttribute('role')).toBe('dialog');
      expect(drawer.getAttribute('aria-modal')).toBe('true');
      expect(menuButton.getAttribute('aria-expanded')).toBe('true');
      expect(document.activeElement).toBe(el.querySelector('[data-testid="menu-close"]'));
      expect(drawer.querySelector('[data-testid="workspace-sections"]')?.textContent).toContain(
        'Ask',
      );
      expect(drawer.querySelector('[data-testid="admin-link"]')).not.toBeNull();

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      await fixture.whenStable();

      expect(el.querySelector('[data-testid="mobile-nav"]')).toBeNull();
      expect(document.activeElement).toBe(menuButton);
    });

    it('closes when a link is followed', async () => {
      const { fixture, el } = await signedIn();
      el.querySelector<HTMLButtonElement>('[data-testid="menu-button"]')!.click();
      await fixture.whenStable();

      el.querySelector<HTMLAnchorElement>(
        '[data-testid="mobile-nav"] [data-testid="workspace-sections"] a',
      )!.click();
      await fixture.whenStable();

      expect(el.querySelector('[data-testid="mobile-nav"]')).toBeNull();
    });

    it('keeps Tab inside the drawer', async () => {
      const { fixture, el } = await signedIn();
      el.querySelector<HTMLButtonElement>('[data-testid="menu-button"]')!.click();
      await fixture.whenStable();
      const drawer = el.querySelector<HTMLElement>('[data-testid="mobile-nav"]')!;
      const focusable = drawer.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), select:not([disabled])',
      );
      const last = focusable[focusable.length - 1];
      last.focus();

      const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
      last.dispatchEvent(tab);

      expect(tab.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(focusable[0]);
    });
  });
});
