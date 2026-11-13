import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { SessionWatch } from './session-watch.service';

/**
 * Attaches the access token to API calls only (never to third-party URLs). A 401 means the session
 * is gone (expired, revoked, or signed out in another tab): {@link SessionWatch} clears it and sends
 * the user to the signed-out home page, remembering where they were.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) {
    return next(req);
  }
  const auth = inject(AuthService);
  const session = inject(SessionWatch);
  const token = auth.accessToken();
  const authorized = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        void session.onUnauthorized();
      }
      return throwError(() => error);
    }),
  );
};
