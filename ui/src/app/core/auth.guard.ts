import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.loggedIn() ? true : inject(Router).createUrlTree(['/login']);
};

/** Route data `roles: string[]`: at least one required. Purely cosmetic; the API is the real gate. */
export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const roles = (route.data['roles'] as string[]) ?? [];
  return auth.hasRole(...roles) ? true : inject(Router).createUrlTree(['/breaks']);
};
