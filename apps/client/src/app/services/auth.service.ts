import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { AuthResponse, Role } from '../models/user.model';

const STORAGE_KEY = 'ec_session';

interface StoredSession {
  token: string;
  id: number;
  email: string;
  role: Role;
  displayName: string;
  avatarUrl?: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private base = '/api/auth';

  private session = signal<StoredSession | null>(this.readStoredSession());

  readonly currentUser = this.session.asReadonly();

  isAuthenticated(): boolean {
    return this.session() !== null;
  }

  getToken(): string | null {
    return this.session()?.token ?? null;
  }

  register(email: string, password: string, displayName: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.base}/register`, { email, password, displayName })
      .pipe(tap((res) => this.storeSession(res)));
  }

  login(email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.base}/login`, { email, password })
      .pipe(tap((res) => this.storeSession(res)));
  }

  registerVolunteer(code: string, email: string, password: string, displayName: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.base}/register/volunteer`, { code, email, password, displayName })
      .pipe(tap((res) => this.storeSession(res)));
  }

  // Always resolves (the server returns 200 regardless of whether the email has an
  // account, to avoid leaking which emails are registered) — the UI shows one generic
  // message either way, see ForgotPasswordComponent.
  forgotPassword(email: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`${this.base}/forgot-password`, { email });
  }

  resetPassword(token: string, newPassword: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`${this.base}/reset-password`, { token, newPassword });
  }

  logout(): void {
    this.session.set(null);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // localStorage unavailable (private browsing, etc.) — nothing else to clean up
    }
  }

  /** Merges a display-name/avatar change into the cached session so the nav and any
   *  other reader of `currentUser()` update immediately — there's no `/api/auth/me`
   *  to refetch from, so the account page pushes the result here after a save. */
  updateProfile(patch: Partial<Pick<StoredSession, 'displayName' | 'avatarUrl'>>): void {
    const current = this.session();
    if (!current) return;
    const updated: StoredSession = { ...current, ...patch };
    this.session.set(updated);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(updated));
    } catch {
      // localStorage unavailable — still works for the rest of this tab's lifetime
    }
  }

  private storeSession(res: AuthResponse): void {
    const session: StoredSession = {
      token: res.token, id: res.id, email: res.email, role: res.role, displayName: res.displayName, avatarUrl: res.avatarUrl,
    };
    this.session.set(session);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } catch {
      // localStorage unavailable — session still works for the rest of this tab's lifetime
    }
  }

  private readStoredSession(): StoredSession | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      const session = JSON.parse(raw) as StoredSession;
      // The server's JWTs expire (24h by default) but the stored session doesn't — without
      // this the app would keep showing a logged-in UI whose every request comes back 401.
      if (isExpired(session.token)) {
        localStorage.removeItem(STORAGE_KEY);
        return null;
      }
      return session;
    } catch {
      return null;
    }
  }
}

/** True if the JWT's `exp` claim is in the past, or the token can't be read at all. */
function isExpired(token: string): boolean {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const { exp } = JSON.parse(atob(payload)) as { exp?: number };
    return typeof exp === 'number' && exp * 1000 <= Date.now();
  } catch {
    return true;
  }
}
