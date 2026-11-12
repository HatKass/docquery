import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { confirmDialog } from '../shared/testing/confirm-dialog.harness';
import { WorkspaceDetailPage } from './workspace-detail.page';
import { Member, WorkspaceRole } from './workspace.model';

const members: Member[] = [
  { userId: 'u1', username: 'owner', displayName: 'Olivia Owner', email: null, role: 'OWNER', joinedAt: '' },
  { userId: 'u2', username: 'max', displayName: 'Max', email: null, role: 'MEMBER', joinedAt: '' },
  { userId: 'u3', username: 'viewer', displayName: 'Vera Viewer', email: null, role: 'VIEWER', joinedAt: '' },
];

const settle = () => new Promise((r) => setTimeout(r));

describe('WorkspaceDetailPage', () => {
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [WorkspaceDetailPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => {
    http.verify();
    confirmDialog.cleanUp();
  });

  const me = (myId: string, myRole: WorkspaceRole | null) => ({
    id: myId,
    username: 'x',
    email: null,
    displayName: 'X',
    workspaces: myRole ? [{ id: 'demo', name: 'Demo workspace', role: myRole, memberCount: 3, createdAt: '' }] : [],
  });

  async function open(workspaceId: string, myRole: WorkspaceRole, myId: string) {
    const fixture = TestBed.createComponent(WorkspaceDetailPage);
    fixture.componentRef.setInput('workspaceId', workspaceId);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(me(myId, myRole));
    await settle();
    if (workspaceId === 'demo') {
      http.expectOne('/api/workspaces/demo/members').flush(members);
    }
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el };
  }

  /** After a confirmed change the page reloads the workspace list and, if still a member, the members. */
  async function flushRefresh(myId: string, myRole: WorkspaceRole | null, updated: Member[] = members) {
    await settle();
    http.expectOne('/api/me').flush(me(myId, myRole));
    await settle();
    if (myRole) {
      http.expectOne('/api/workspaces/demo/members').flush(updated);
      await settle();
    }
  }

  function chooseRole(el: HTMLElement, userId: string, role: WorkspaceRole): HTMLSelectElement {
    const select = el.querySelector<HTMLSelectElement>(`[data-testid="role-select-${userId}"]`)!;
    select.focus();
    select.value = role;
    select.dispatchEvent(new Event('change'));
    return select;
  }

  it('shows members and management controls to owners', async () => {
    const { el } = await open('demo', 'OWNER', 'u1');
    expect(el.querySelector('[data-testid="workspace-name"]')?.textContent).toContain('Demo workspace');
    expect(el.querySelector('[data-testid="my-role"]')?.textContent).toBe('OWNER');
    expect(el.querySelectorAll('[data-testid="member-table"] tbody tr').length).toBe(3);
    expect(el.textContent).toContain('Add member');
    expect(el.textContent).toContain('Delete workspace');
  });

  it('hides management controls from viewers but lets them leave', async () => {
    const { el } = await open('demo', 'VIEWER', 'u3');
    expect(el.querySelector('[data-testid="my-role"]')?.textContent).toBe('VIEWER');
    expect(el.textContent).not.toContain('Add member');
    expect(el.textContent).not.toContain('Delete workspace');
    expect(el.querySelectorAll('tbody select').length).toBe(0);
    const buttons = Array.from(el.querySelectorAll('tbody button')).map((b) => b.textContent?.trim());
    expect(buttons).toEqual(['Leave']);
  });

  describe('role changes', () => {
    it('confirms a normal role change with the default variant and the consequence', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');

      chooseRole(el, 'u2', 'VIEWER');
      await settle();

      expect(confirmDialog.variant()).toBe('default');
      expect(confirmDialog.title()).toBe("Change Max's role from MEMBER to VIEWER?");
      expect(confirmDialog.message()).toBe('Viewers can read documents but cannot upload, delete or manage members.');
      expect(confirmDialog.accept().textContent?.trim()).toBe('Change role');

      confirmDialog.accept().click();
      const put = http.expectOne('/api/workspaces/demo/members/u2');
      expect(put.request.method).toBe('PUT');
      expect(put.request.body).toEqual({ role: 'VIEWER' });
      put.flush({ ...members[1], role: 'VIEWER' });
      await flushRefresh('u1', 'OWNER', [members[0], { ...members[1], role: 'VIEWER' }, members[2]]);

      expect(confirmDialog.element()).toBeNull();
    });

    it('reverts the dropdown to the stored role when cancelled, without calling the API', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');

      const select = chooseRole(el, 'u2', 'VIEWER');
      await settle();
      confirmDialog.cancel().click();
      await settle();

      expect(select.value).toBe('MEMBER');
      expect(confirmDialog.element()).toBeNull();
      expect(document.activeElement).toBe(select);
      http.expectNone('/api/workspaces/demo/members/u2');
    });

    it('reverts the dropdown when the dialog is closed with Escape', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');

      const select = chooseRole(el, 'u3', 'MEMBER');
      await settle();
      confirmDialog.pressEscape();
      await settle();

      expect(select.value).toBe('VIEWER');
      http.expectNone('/api/workspaces/demo/members/u3');
    });

    it('uses the danger variant with a strong warning when promoting someone to OWNER', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');

      chooseRole(el, 'u2', 'OWNER');
      await settle();

      expect(confirmDialog.variant()).toBe('danger');
      expect(confirmDialog.accept().className).toContain('bg-red-600');
      expect(confirmDialog.title()).toBe("Change Max's role from MEMBER to OWNER?");
      expect(confirmDialog.message()).toContain('remove other owners (including you)');
      expect(confirmDialog.message()).toContain('delete Demo workspace');
      expect(confirmDialog.accept().textContent?.trim()).toBe('Make owner');

      confirmDialog.accept().click();
      http.expectOne('/api/workspaces/demo/members/u2').flush({ ...members[1], role: 'OWNER' });
      await flushRefresh('u1', 'OWNER');
      expect(confirmDialog.element()).toBeNull();
    });

    it('uses the danger variant when owners change their own role', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');

      chooseRole(el, 'u1', 'MEMBER');
      await settle();

      expect(confirmDialog.variant()).toBe('danger');
      expect(confirmDialog.title()).toBe('Change your own role from OWNER to MEMBER?');
      expect(confirmDialog.message()).toContain('Members can read, upload and delete documents');
      expect(confirmDialog.message()).toContain('lose access to members and settings');
      expect(confirmDialog.accept().textContent?.trim()).toBe('Change my role');
    });

    it('shows a rejected change inside the dialog and reverts when the user then cancels', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');

      const select = chooseRole(el, 'u1', 'VIEWER');
      await settle();
      confirmDialog.accept().click();
      http.expectOne('/api/workspaces/demo/members/u1').flush(
        { type: 'urn:docquery:problem:last-owner', detail: 'A workspace needs at least one owner.' },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();

      expect(confirmDialog.element()).not.toBeNull();
      expect(confirmDialog.error()).toContain('at least one owner');
      expect(el.querySelector('[data-testid="detail-error"]')).toBeNull();

      confirmDialog.cancel().click();
      await settle();
      expect(select.value).toBe('OWNER');
      expect(confirmDialog.element()).toBeNull();
    });
  });

  describe('destructive actions', () => {
    it('confirms removing a member with the danger variant, then refreshes', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');
      const button = el.querySelector<HTMLButtonElement>('[data-testid="remove-member-u2"]')!;
      expect(button.getAttribute('aria-label')).toBe('Remove Max');

      button.focus();
      button.click();
      await settle();
      expect(confirmDialog.variant()).toBe('danger');
      expect(confirmDialog.title()).toBe('Remove Max from Demo workspace?');
      expect(confirmDialog.message()).toContain('lose access');

      confirmDialog.accept().click();
      const del = http.expectOne('/api/workspaces/demo/members/u2');
      expect(del.request.method).toBe('DELETE');
      del.flush(null);
      await flushRefresh('u1', 'OWNER', [members[0], members[2]]);

      expect(el.querySelectorAll('[data-testid="member-table"] tbody tr').length).toBe(2);
      expect(navigate).not.toHaveBeenCalled();
    });

    it('does nothing when removing a member is cancelled, and returns focus to the button', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');
      const button = el.querySelector<HTMLButtonElement>('[data-testid="remove-member-u3"]')!;

      button.focus();
      button.click();
      await settle();
      confirmDialog.cancel().click();
      await settle();

      http.expectNone('/api/workspaces/demo/members/u3');
      expect(document.activeElement).toBe(button);
    });

    it('confirms leaving the workspace, then goes back to the workspace list', async () => {
      const { el } = await open('demo', 'VIEWER', 'u3');

      el.querySelector<HTMLButtonElement>('[data-testid="leave-workspace"]')!.click();
      await settle();
      expect(confirmDialog.variant()).toBe('danger');
      expect(confirmDialog.title()).toBe('Leave Demo workspace?');

      confirmDialog.accept().click();
      http.expectOne('/api/workspaces/demo/members/u3').flush(null);
      await flushRefresh('u3', null);

      expect(navigate).toHaveBeenCalledWith(['/workspaces']);
    });

    it('keeps the dialog open with the reason when the last owner tries to leave', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');

      el.querySelector<HTMLButtonElement>('[data-testid="leave-workspace"]')!.click();
      await settle();
      confirmDialog.accept().click();
      http.expectOne('/api/workspaces/demo/members/u1').flush(
        { detail: 'Transfer ownership before leaving: a workspace needs at least one owner.' },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();

      expect(confirmDialog.error()).toContain('Transfer ownership');
      expect(navigate).not.toHaveBeenCalled();
    });

    it('confirms deleting the workspace with its consequences, then leaves the page', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');

      el.querySelector<HTMLButtonElement>('[data-testid="delete-workspace"]')!.click();
      await settle();
      expect(confirmDialog.variant()).toBe('danger');
      expect(confirmDialog.title()).toBe('Delete Demo workspace?');
      expect(confirmDialog.message()).toContain('text chunks and embeddings');
      expect(confirmDialog.accept().textContent?.trim()).toBe('Delete workspace');

      confirmDialog.accept().click();
      const del = http.expectOne('/api/workspaces/demo');
      expect(del.request.method).toBe('DELETE');
      del.flush(null);
      await flushRefresh('u1', null);

      expect(navigate).toHaveBeenCalledWith(['/workspaces']);
    });

    it('does not delete the workspace when cancelled', async () => {
      const { el } = await open('demo', 'OWNER', 'u1');

      el.querySelector<HTMLButtonElement>('[data-testid="delete-workspace"]')!.click();
      await settle();
      confirmDialog.pressEscape();
      await settle();

      http.expectNone('/api/workspaces/demo');
      expect(navigate).not.toHaveBeenCalled();
    });
  });

  it('shows "not found" for a workspace the user is not a member of', async () => {
    const { el } = await open('someone-elses', 'OWNER', 'u1');
    expect(el.querySelector('[data-testid="workspace-not-found"]')).not.toBeNull();
  });
});
