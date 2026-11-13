import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AuthService } from './auth.service';
import { SessionWatch } from './session-watch.service';

/**
 * Signed-out users go to the home page, which offers "Sign in" and returns them here afterwards
 * (the same place a 401 or a restored page after sign-out leads to).
 */
export const authGuard: CanActivateFn = (_route, state) => {
  if (inject(AuthService).isAuthenticated()) {
    return true;
  }
  return inject(SessionWatch).signedOutTree(state.url);
};
