import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DocumentSummary, UploadPolicy } from './document.model';

/** Thin typed client for the api-service document endpoints. */
@Injectable({ providedIn: 'root' })
export class DocumentApi {
  private readonly http = inject(HttpClient);

  uploadPolicy(): Observable<UploadPolicy> {
    return this.http.get<UploadPolicy>('/api/documents/upload-policy');
  }

  list(workspaceId: string): Observable<DocumentSummary[]> {
    return this.http.get<DocumentSummary[]>(`/api/workspaces/${workspaceId}/documents`);
  }

  upload(workspaceId: string, file: File): Observable<DocumentSummary> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<DocumentSummary>(`/api/workspaces/${workspaceId}/documents`, body);
  }

  delete(workspaceId: string, documentId: string): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${workspaceId}/documents/${documentId}`);
  }

  /** The original file; fetched with the bearer token, so it cannot be a plain link. */
  content(workspaceId: string, documentId: string): Observable<Blob> {
    return this.http.get(`/api/workspaces/${workspaceId}/documents/${documentId}/content`, { responseType: 'blob' });
  }
}
