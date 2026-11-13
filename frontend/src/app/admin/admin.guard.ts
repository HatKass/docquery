import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { WorkspaceContext } from '../workspaces/workspace-context.service';

/**
 * Keeps non-admins off the admin page (they would only see 403s). A convenience, not security: the
 * API enforces the admin role on every /api/admin request. Runs after authGuard.
 */
export const adminGuard: CanActivateFn = async () => {
  const context = inject(WorkspaceContext);
  const router = inject(Router);
  if (!context.loaded()) {
    try {
      await context.load();
    } catch {
      return router.parseUrl('/');
    }
  }
  return context.isAdmin() ? true : router.parseUrl('/');
};
