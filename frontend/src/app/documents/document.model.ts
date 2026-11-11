/** Pipeline status, mirrored from api-service `DocumentStatus`. */
export type DocumentStatus = 'UPLOADED' | 'EXTRACTING' | 'CHUNKING' | 'EMBEDDING' | 'READY' | 'FAILED';

export const IN_PROGRESS_STATUSES: readonly DocumentStatus[] = ['UPLOADED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING'];

export interface DocumentSummary {
  id: string;
  workspaceId: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  status: DocumentStatus;
  failureReason: string | null;
  pageCount: number | null;
  chunkCount: number | null;
  uploadedBy: string | null;
  uploadedByName: string | null;
  uploadedAt: string;
  statusChangedAt: string;
}

export interface UploadPolicy {
  maxFileSizeBytes: number;
  allowedExtensions: string[];
  allowedContentTypes: string[];
}

/** One message from the live document stream. */
export type DocumentStreamMessage =
  | { type: 'ready' }
  | { type: 'reconnecting' }
  | { type: 'created' | 'updated'; document: DocumentSummary }
  | { type: 'deleted'; documentId: string };
