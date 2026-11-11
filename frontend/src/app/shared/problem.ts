import { HttpErrorResponse } from '@angular/common/http';

/** RFC 9457 problem details, as returned by every api-service error. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  errors?: { field: string; message: string }[];
}

/** A human-readable message for any HTTP error, preferring the API's own explanation. */
export function problemMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const problem = error.error as ProblemDetail | null;
    if (problem?.errors?.length) {
      return problem.errors.map((e) => `${e.field}: ${e.message}`).join('; ');
    }
    if (problem?.detail) {
      return problem.detail;
    }
    if (error.status === 0) {
      return 'The API is not reachable. Is api-service running?';
    }
    return `Request failed (${error.status}).`;
  }
  return 'Something went wrong.';
}
