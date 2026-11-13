import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { UsageReport } from '../usage/usage.model';
import {
  AdminOverview,
  AdminUser,
  DeadLetter,
  DeadLetterStatus,
  FailedDocument,
} from './admin.model';

/** /api/admin/** (admin role required by the API). */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);

  overview(): Observable<AdminOverview> {
    return this.http.get<AdminOverview>('/api/admin/overview');
  }

  usage(days: number): Observable<UsageReport> {
    return this.http.get<UsageReport>('/api/admin/usage', { params: { days } });
  }

  failures(): Observable<FailedDocument[]> {
    return this.http.get<FailedDocument[]>('/api/admin/failures');
  }

  reindexDocument(documentId: string): Observable<unknown> {
    return this.http.post(`/api/admin/documents/${documentId}/reindex`, {});
  }

  deadLetters(status: DeadLetterStatus | 'ALL'): Observable<DeadLetter[]> {
    return this.http.get<DeadLetter[]>('/api/admin/dead-letters', { params: { status } });
  }

  retryDeadLetter(id: string): Observable<DeadLetter> {
    return this.http.post<DeadLetter>(`/api/admin/dead-letters/${id}/retry`, {});
  }

  dismissDeadLetter(id: string): Observable<DeadLetter> {
    return this.http.post<DeadLetter>(`/api/admin/dead-letters/${id}/dismiss`, {});
  }

  users(query: string): Observable<AdminUser[]> {
    return this.http.get<AdminUser[]>('/api/admin/users', { params: query ? { q: query } : {} });
  }

  /** null restores the default; 0 means no limit. */
  setWorkspaceLimit(userId: string, limit: number | null): Observable<AdminUser> {
    return this.http.put<AdminUser>(`/api/admin/users/${userId}/workspace-limit`, { limit });
  }
}
