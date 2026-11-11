import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { WorkspaceDetailPage } from './workspace-detail.page';
import { Member, WorkspaceRole } from './workspace.model';

const members: Member[] = [
  { userId: 'u1', username: 'owner', displayName: 'Olivia Owner', email: null, role: 'OWNER', joinedAt: '' },
  { userId: 'u3', username: 'viewer', displayName: 'Vera Viewer', email: null, role: 'VIEWER', joinedAt: '' },
];

describe('WorkspaceDetailPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [WorkspaceDetailPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open(workspaceId: string, myRole: WorkspaceRole, myId: string) {
    const fixture = TestBed.createComponent(WorkspaceDetailPage);
    fixture.componentRef.setInput('workspaceId', workspaceId);
    fixture.detectChanges();
    http.expectOne('/api/me').flush({
      id: myId,
      username: 'x',
      email: null,
      displayName: 'X',
      workspaces: [{ id: 'demo', name: 'Demo workspace', role: myRole, memberCount: 2, createdAt: '' }],
    });
    await new Promise((r) => setTimeout(r));
    if (workspaceId === 'demo') {
      http.expectOne('/api/workspaces/demo/members').flush(members);
    }
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows members and management controls to owners', async () => {
    const el = await open('demo', 'OWNER', 'u1');
    expect(el.querySelector('[data-testid="workspace-name"]')?.textContent).toContain('Demo workspace');
    expect(el.querySelector('[data-testid="my-role"]')?.textContent).toBe('OWNER');
    expect(el.querySelectorAll('[data-testid="member-table"] tbody tr').length).toBe(2);
    expect(el.textContent).toContain('Add member');
    expect(el.textContent).toContain('Delete workspace');
  });

  it('hides management controls from viewers but lets them leave', async () => {
    const el = await open('demo', 'VIEWER', 'u3');
    expect(el.querySelector('[data-testid="my-role"]')?.textContent).toBe('VIEWER');
    expect(el.textContent).not.toContain('Add member');
    expect(el.textContent).not.toContain('Delete workspace');
    expect(el.querySelectorAll('tbody select').length).toBe(0);
    const buttons = Array.from(el.querySelectorAll('tbody button')).map((b) => b.textContent?.trim());
    expect(buttons).toEqual(['Leave']);
  });

  it('puts the role dropdown back and shows the reason when a role change is rejected', async () => {
    const el = await open('demo', 'OWNER', 'u1');
    const ownerSelect = el.querySelector<HTMLSelectElement>('#role-u1')!;

    ownerSelect.value = 'VIEWER';
    ownerSelect.dispatchEvent(new Event('change'));
    http.expectOne('/api/workspaces/demo/members/u1').flush(
      { type: 'urn:docquery:problem:last-owner', detail: 'A workspace needs at least one owner.' },
      { status: 409, statusText: 'Conflict' },
    );
    await new Promise((r) => setTimeout(r));
    http.expectOne('/api/workspaces/demo/members').flush(members);
    await new Promise((r) => setTimeout(r));

    expect(ownerSelect.value).toBe('OWNER');
    expect(el.querySelector('[data-testid="detail-error"]')?.textContent).toContain('at least one owner');
  });

  it('shows "not found" for a workspace the user is not a member of', async () => {
    const el = await open('someone-elses', 'OWNER', 'u1');
    expect(el.querySelector('[data-testid="workspace-not-found"]')).not.toBeNull();
  });
});
