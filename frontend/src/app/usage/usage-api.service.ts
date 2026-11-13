import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MyQuotas, UsageReport } from './usage.model';

@Injectable({ providedIn: 'root' })
export class UsageApi {
  private readonly http = inject(HttpClient);

  myQuotas(): Observable<MyQuotas> {
    return this.http.get<MyQuotas>('/api/me/quotas');
  }

  /** Owners only. */
  workspaceUsage(workspaceId: string, days: number): Observable<UsageReport> {
    return this.http.get<UsageReport>(`/api/workspaces/${workspaceId}/usage`, { params: { days } });
  }
}
