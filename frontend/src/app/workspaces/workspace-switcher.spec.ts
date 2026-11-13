import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { WorkspaceContext } from './workspace-context.service';
import { WorkspaceSwitcher } from './workspace-switcher';

describe('WorkspaceSwitcher', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [WorkspaceSwitcher],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  async function render(workspaces: { id: string; name: string; role: string }[]) {
    const context = TestBed.inject(WorkspaceContext);
    const loading = context.load();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/me')
      .flush({ id: 'u1', username: 'u', email: null, displayName: 'U', workspaces });
    await loading;
    const fixture = TestBed.createComponent(WorkspaceSwitcher);
    await fixture.whenStable();
    return { fixture, context, el: fixture.nativeElement as HTMLElement };
  }

  it('lists every workspace and shows the role in the current one as a badge', async () => {
    const { el } = await render([
      { id: 'a', name: 'Alpha', role: 'OWNER' },
      { id: 'b', name: 'Beta', role: 'VIEWER' },
    ]);
    const options = Array.from(el.querySelectorAll('option')).map((o) => o.textContent?.trim());
    expect(options).toEqual(['Alpha', 'Beta']);
    expect(el.querySelector('[data-testid="role-badge"]')?.textContent?.trim()).toBe('OWNER');
  });

  it('switches workspace and navigates to it', async () => {
    const { el, context, fixture } = await render([
      { id: 'a', name: 'Alpha', role: 'OWNER' },
      { id: 'b', name: 'Beta', role: 'VIEWER' },
    ]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const select = el.querySelector<HTMLSelectElement>('[data-testid="workspace-switcher"]')!;
    select.value = 'b';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(context.current()?.id).toBe('b');
    expect(navigate).toHaveBeenCalledWith(['/workspaces', 'b']);
    expect(el.querySelector('[data-testid="role-badge"]')?.textContent?.trim()).toBe('VIEWER');
  });

  it('stays in the same section when switching from Ask or Members', async () => {
    const { el, fixture } = await render([
      { id: 'a', name: 'Alpha', role: 'OWNER' },
      { id: 'b', name: 'Beta', role: 'VIEWER' },
    ]);
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/workspaces/a/chat?conversation=c1');
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const select = el.querySelector<HTMLSelectElement>('[data-testid="workspace-switcher"]')!;
    select.value = 'b';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith(['/workspaces', 'b', 'chat']);
  });

  it('offers to create one when the user has no workspace', async () => {
    const { el } = await render([]);
    expect(el.querySelector('a')?.textContent).toContain('Create a workspace');
    expect(el.querySelector('[data-testid="role-badge"]')).toBeNull();
  });
});
