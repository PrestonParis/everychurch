import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Claim, ClaimStatus } from '../models/claim.model';

@Injectable({ providedIn: 'root' })
export class ClaimService {
  private http = inject(HttpClient);

  /** Any logged-in user can ask to own an unclaimed church; a Platform Admin decides. */
  request(churchId: number, notes: string): Observable<Claim> {
    return this.http.post<Claim>(`/api/churches/${churchId}/claims`, { notes: notes.trim() || null });
  }

  /** The claims the caller has submitted themselves — Settings > Church connections. */
  myClaims(): Observable<Claim[]> {
    return this.http.get<Claim[]>('/api/users/me/claims');
  }

  // Platform Admin only
  list(status?: ClaimStatus): Observable<Claim[]> {
    return this.http.get<Claim[]>('/api/admin/claims', { params: status ? { status } : {} });
  }

  approve(id: number): Observable<Claim> {
    return this.http.post<Claim>(`/api/admin/claims/${id}/approve`, {});
  }

  reject(id: number): Observable<Claim> {
    return this.http.post<Claim>(`/api/admin/claims/${id}/reject`, {});
  }
}
