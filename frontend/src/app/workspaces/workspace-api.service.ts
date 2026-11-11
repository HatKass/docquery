import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Me, Member, Workspace, WorkspaceRole } from './workspace.model';

/** Thin typed client for the api-service workspace endpoints. */
@Injectable({ providedIn: 'root' })
export class WorkspaceApi {
  private readonly http = inject(HttpClient);

  me(): Observable<Me> {
    return this.http.get<Me>('/api/me');
  }

  create(name: string): Observable<Workspace> {
    return this.http.post<Workspace>('/api/workspaces', { name });
  }

  rename(id: string, name: string): Observable<Workspace> {
    return this.http.patch<Workspace>(`/api/workspaces/${id}`, { name });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${id}`);
  }

  members(id: string): Observable<Member[]> {
    return this.http.get<Member[]>(`/api/workspaces/${id}/members`);
  }

  addMember(id: string, usernameOrEmail: string, role: WorkspaceRole): Observable<Member> {
    return this.http.post<Member>(`/api/workspaces/${id}/members`, { usernameOrEmail, role });
  }

  changeRole(id: string, userId: string, role: WorkspaceRole): Observable<Member> {
    return this.http.put<Member>(`/api/workspaces/${id}/members/${userId}`, { role });
  }

  removeMember(id: string, userId: string): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${id}/members/${userId}`);
  }
}
