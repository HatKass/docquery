/** Mirrors api-service's AdminDtos and DeadLetterDtos. */

export interface AdminOverview {
  documentsByStatus: Record<string, number>;
  pendingDeadLetters: number;
  users: number;
  workspaces: number;
  defaultWorkspaceLimit: number;
}

export interface FailedDocument {
  documentId: string;
  workspaceId: string;
  workspaceName: string;
  filename: string;
  contentType: string;
  failureReason: string;
  failedAt: string;
  uploadedBy: string | null;
}

export type DeadLetterStatus = 'PENDING' | 'RETRIED' | 'DISMISSED';

export interface DeadLetter {
  id: string;
  status: DeadLetterStatus;
  eventId: string | null;
  documentId: string | null;
  documentName: string | null;
  documentStatus: string | null;
  workspaceId: string | null;
  workspaceName: string | null;
  /** Root cause in plain words (framework wrappers removed). */
  errorSummary: string;
  exceptionClass: string | null;
  exceptionMessage: string | null;
  originalTopic: string | null;
  originalPartition: number | null;
  originalOffset: number | null;
  failedAt: string;
  resolvedAt: string | null;
  resolvedBy: string | null;
  retryable: boolean;
  payload: string | null;
}

export interface AdminUser {
  id: string;
  username: string;
  displayName: string;
  email: string | null;
  lastSeenAt: string;
  workspacesCreated: number;
  /** null: the default applies; 0: no limit */
  workspaceLimitOverride: number | null;
  /** The limit in effect; null: no limit */
  workspaceLimit: number | null;
  questionsToday: number;
  uploadsToday: number;
}
