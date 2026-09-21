import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // Direct-to-S3 presigned uploads (Prompt 8/9) hit an absolute, external URL —
  // never attach our JWT there, it has nothing to do with S3's own presigned-URL
  // auth and could confuse it.
  const token = req.url.startsWith('/') ? inject(AuthService).getToken() : null;
  if (!token) return next(req);
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
