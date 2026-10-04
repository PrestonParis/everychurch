import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // Direct-to-S3 presigned uploads (Prompt 8/9) hit an absolute, external URL —
  // never attach our JWT there, it has nothing to do with S3's own presigned-URL
  // auth and could confuse it.
  const authService = inject(AuthService);
  const token = req.url.startsWith('/') ? authService.getToken() : null;
  if (!token) return next(req);
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })).pipe(
    catchError((err: unknown) => {
      // A 401 on a request that carried our token means the server no longer accepts it
      // (expired, signed with a rotated secret, or the account is gone). Drop the session
      // so the UI falls back to its logged-out state ("Log in to …") instead of offering
      // actions that can only fail. /api/auth/** is excluded: a 401 there is a wrong
      // password, not a dead session.
      if (err instanceof HttpErrorResponse && err.status === 401 && !req.url.startsWith('/api/auth/')) {
        authService.logout();
      }
      return throwError(() => err);
    }),
  );
};
