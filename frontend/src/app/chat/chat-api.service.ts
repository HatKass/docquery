import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConversationDetail, ConversationSummary } from './chat.model';

/** Typed client for the conversation endpoints (asking a question streams: see AnswerStream). */
@Injectable({ providedIn: 'root' })
export class ChatApi {
  private readonly http = inject(HttpClient);

  list(workspaceId: string): Observable<ConversationSummary[]> {
    return this.http.get<ConversationSummary[]>(`/api/workspaces/${workspaceId}/conversations`);
  }

  create(workspaceId: string): Observable<ConversationSummary> {
    return this.http.post<ConversationSummary>(`/api/workspaces/${workspaceId}/conversations`, {});
  }

  get(workspaceId: string, conversationId: string): Observable<ConversationDetail> {
    return this.http.get<ConversationDetail>(`/api/workspaces/${workspaceId}/conversations/${conversationId}`);
  }

  delete(workspaceId: string, conversationId: string): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${workspaceId}/conversations/${conversationId}`);
  }
}
