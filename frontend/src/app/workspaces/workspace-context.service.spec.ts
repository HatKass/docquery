import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { WorkspaceContext } from './workspace-context.service';
import { Me, Workspace } from './workspace.model';

const ws = (id: string, name: string, role: Workspace['role'] = 'OWNER'): Workspace => ({
  id,
  name,
  role,
  memberCount: 1,
  createdAt: '2026-11-09T10:00:00Z',
});

const me = (workspaces: Workspace[]): Me => ({
  id: 'u1',
  username: 'owner',
  email: 'owner@docquery.local',
  displayName: 'Olivia Owner',
  admin: false,
  workspaces,
});

describe('WorkspaceContext', () => {
  let context: WorkspaceContext;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    context = TestBed.inject(WorkspaceContext);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function load(workspaces: Workspace[]): Promise<void> {
    const done = context.load();
    http.expectOne('/api/me').flush(me(workspaces));
    await done;
  }

  it('defaults to the first workspace', async () => {
    await load([ws('a', 'Alpha'), ws('b', 'Beta')]);
    expect(context.loaded()).toBe(true);
    expect(context.current()?.id).toBe('a');
  });

  it('remembers the selected workspace across reloads', async () => {
    await load([ws('a', 'Alpha'), ws('b', 'Beta')]);
    context.select('b');
    expect(context.current()?.name).toBe('Beta');
    expect(localStorage.getItem('docquery.currentWorkspace')).toBe('b');
  });

  it('falls back when the remembered workspace is no longer accessible', async () => {
    localStorage.setItem('docquery.currentWorkspace', 'gone');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    context = TestBed.inject(WorkspaceContext);
    http = TestBed.inject(HttpTestingController);

    await load([ws('a', 'Alpha')]);
    expect(context.current()?.id).toBe('a');
  });

  it('shares one request between concurrent loads', async () => {
    const first = context.load();
    const second = context.load();
    http.expectOne('/api/me').flush(me([]));
    await Promise.all([first, second]);
    expect(context.workspaces()).toEqual([]);
  });

  it('creates a workspace, reloads and selects it', async () => {
    const created = context.create('Legal');
    const post = http.expectOne('/api/workspaces');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ name: 'Legal' });
    post.flush(ws('n', 'Legal'));
    await Promise.resolve();
    await Promise.resolve();
    http.expectOne('/api/me').flush(me([ws('a', 'Alpha'), ws('n', 'Legal')]));
    await created;
    expect(context.current()?.id).toBe('n');
  });
});
