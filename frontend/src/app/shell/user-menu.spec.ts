import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { vi } from 'vitest';
import {
  FakeUserManager,
  fakeUser,
  provideFakeUserManager,
} from '../auth/testing/fake-user-manager';
import { AuthService } from '../auth/auth.service';
import { WorkspaceContext } from '../workspaces/workspace-context.service';
import { UserMenu, initials } from './user-menu';

const settle = () => new Promise((r) => setTimeout(r));

describe('UserMenu', () => {
  let fake: FakeUserManager;

  beforeEach(async () => {
    fake = new FakeUserManager();
    fake.storedUser = fakeUser();
    TestBed.configureTestingModule({
      imports: [UserMenu],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideFakeUserManager(fake),
      ],
    });
    await TestBed.inject(AuthService).init();
    const loading = TestBed.inject(WorkspaceContext).load();
    TestBed.inject(HttpTestingController).expectOne('/api/me').flush({
      id: 'u1',
      username: 'owner',
      email: null,
      displayName: 'Olivia Owner',
      admin: false,
      workspaces: [],
    });
    await loading;
  });

  async function render() {
    const fixture = TestBed.createComponent(UserMenu);
    document.body.appendChild(fixture.nativeElement);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const button = el.querySelector<HTMLButtonElement>('[data-testid="user-menu-button"]')!;
    return { fixture, el, button };
  }

  const items = (el: HTMLElement) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('[role="menuitem"]'));
  const key = (target: Element, k: string) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));

  it('shows the initials and is a closed menu button', async () => {
    const { el, button } = await render();
    expect(el.querySelector('[data-testid="avatar"]')?.textContent).toBe('OO');
    expect(button.getAttribute('aria-haspopup')).toBe('menu');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(el.querySelector('[role="menu"]')).toBeNull();
  });

  it('opens on click with the name, username and items, and focuses the first item', async () => {
    const { fixture, el, button } = await render();
    button.click();
    await fixture.whenStable();
    await settle();

    const menu = el.querySelector('[role="menu"]')!;
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(menu.getAttribute('aria-labelledby')).toBe('user-menu-button');
    expect(menu.textContent).toContain('Olivia Owner');
    expect(menu.textContent).toContain('@owner');
    expect(items(el).map((i) => i.textContent?.trim())).toEqual(['Manage workspaces', 'Sign out']);
    expect(document.activeElement).toBe(items(el)[0]);
  });

  it('supports the keyboard: ArrowUp opens on the last item, arrows wrap, Escape closes and refocuses', async () => {
    const { fixture, el, button } = await render();
    key(button, 'ArrowUp');
    await fixture.whenStable();
    await settle();
    expect(document.activeElement).toBe(items(el)[1]);

    key(document.activeElement!, 'ArrowDown');
    expect(document.activeElement).toBe(items(el)[0]);
    key(document.activeElement!, 'End');
    expect(document.activeElement).toBe(items(el)[1]);
    key(document.activeElement!, 'Home');
    expect(document.activeElement).toBe(items(el)[0]);

    key(document.activeElement!, 'Escape');
    await fixture.whenStable();
    expect(el.querySelector('[role="menu"]')).toBeNull();
    expect(document.activeElement).toBe(button);
  });

  it('closes on a click outside', async () => {
    const { fixture, el, button } = await render();
    button.click();
    await fixture.whenStable();

    document.body.click();
    await fixture.whenStable();

    expect(el.querySelector('[role="menu"]')).toBeNull();
  });

  it('goes to the workspace list and signs out', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const { fixture, el, button } = await render();
    button.click();
    await fixture.whenStable();
    items(el)[0].click();
    expect(navigate).toHaveBeenCalledWith(['/workspaces']);

    button.click();
    await fixture.whenStable();
    items(el)[1].click();
    expect(fake.signoutRedirect).toHaveBeenCalled();
  });

  it('derives initials', () => {
    expect(initials('Olivia Owner')).toBe('OO');
    expect(initials('Ada Lovelace King')).toBe('AK');
    expect(initials('max')).toBe('M');
    expect(initials('  ')).toBe('?');
  });
});
