import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { AuthResponse, Role } from '../models/user.model';

const STORAGE_KEY = 'ec_session';

interface StoredSession {
  token: string;
  email: string;
  role: Role;
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

  register(email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.base}/register`, { email, password })
      .pipe(tap((res) => this.storeSession(res)));
  }

  login(email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.base}/login`, { email, password })
      .pipe(tap((res) => this.storeSession(res)));
  }

  registerVolunteer(code: string, email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.base}/register/volunteer`, { code, email, password })
      .pipe(tap((res) => this.storeSession(res)));
  }

  logout(): void {
    this.session.set(null);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // localStorage unavailable (private browsing, etc.) — nothing else to clean up
    }
  }

  private storeSession(res: AuthResponse): void {
    const session: StoredSession = { token: res.token, email: res.email, role: res.role };
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
      return raw ? (JSON.parse(raw) as StoredSession) : null;
    } catch {
      return null;
    }
  }
}
