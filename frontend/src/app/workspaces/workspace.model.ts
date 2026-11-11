export type WorkspaceRole = 'OWNER' | 'MEMBER' | 'VIEWER';

export const WORKSPACE_ROLES: readonly WorkspaceRole[] = ['OWNER', 'MEMBER', 'VIEWER'];

export interface Workspace {
  id: string;
  name: string;
  /** The signed-in user's role in this workspace. */
  role: WorkspaceRole;
  memberCount: number;
  createdAt: string;
}

export interface Member {
  userId: string;
  username: string;
  displayName: string;
  email: string | null;
  role: WorkspaceRole;
  joinedAt: string;
}

export interface Me {
  id: string;
  username: string;
  email: string | null;
  displayName: string;
  workspaces: Workspace[];
}
