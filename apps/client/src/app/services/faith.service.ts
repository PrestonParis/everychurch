import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { AuthService } from './auth.service';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  FaithCatalog,
  FaithEditor,
  FaithEvaluation,
  FaithProfile,
  FaithProfileInput,
  FaithResult,
  FaithSaved,
} from '../models/faith.model';

@Injectable({ providedIn: 'root' })
export class FaithService {
  private http = inject(HttpClient);
  private auth = inject(AuthService);
  constructor() {
    let previous = this.auth.currentUser()?.id;
    effect(() => {
      const next = this.auth.currentUser()?.id;
      if (previous !== undefined && next !== previous)
        untracked(() => this.clear());
      previous = next;
    });
  }
  // In-memory only. Anonymous answers never go into URLs, analytics or persistent browser storage.
  request = signal<FaithEvaluation | null>(null);
  result = signal<FaithResult | null>(null);
  catalog(version?: string) {
    return this.http.get<FaithCatalog>(
      version
        ? `/api/learn/quiz/versions/${encodeURIComponent(version)}`
        : '/api/learn/quiz',
    );
  }
  evaluate(request: FaithEvaluation) {
    return this.http.post<FaithResult>('/api/learn/quiz/evaluate', request);
  }
  profile(id: number) {
    return this.http.get<FaithProfile>(`/api/churches/${id}/beliefs`);
  }
  editor(id: number) {
    return this.http.get<FaithEditor>(`/api/churches/${id}/beliefs/editor`);
  }
  draft(id: number, input: FaithProfileInput) {
    return this.http.put<FaithProfile>(
      `/api/churches/${id}/beliefs/draft`,
      input,
    );
  }
  publish(id: number, input: FaithProfileInput) {
    return this.http.post<FaithProfile>(
      `/api/churches/${id}/beliefs/publish`,
      input,
    );
  }
  saved() {
    return this.http.get<FaithSaved[]>('/api/users/me/quiz-attempts');
  }
  save(request: FaithEvaluation) {
    return this.http.post<FaithSaved>('/api/users/me/quiz-attempts', request);
  }
  delete(id: number) {
    return this.http.delete<void>(`/api/users/me/quiz-attempts/${id}`);
  }
  clear() {
    this.request.set(null);
    this.result.set(null);
  }
}
export function faithError(error: HttpErrorResponse): string {
  if (error.status === 401) return 'Please sign in to continue.';
  if (error.status === 403)
    return 'You need Manage Beliefs permission for this church.';
  if (error.status === 409)
    return 'This profile changed while you were editing. Reload before publishing.';
  return error.error?.error && error.status === 400
    ? error.error.error
    : 'We could not complete that request. Please try again.';
}
