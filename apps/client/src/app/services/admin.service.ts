import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { VolunteerInvite } from '../models/volunteer-invite.model';

@Injectable({ providedIn: 'root' })
export class AdminService {
  private http = inject(HttpClient);
  private base = '/api/admin/volunteer-invites';

  listInvites(): Observable<VolunteerInvite[]> {
    return this.http.get<VolunteerInvite[]>(this.base);
  }

  generateInvite(expiresInHours?: number): Observable<VolunteerInvite> {
    return this.http.post<VolunteerInvite>(this.base, { expiresInHours: expiresInHours ?? null });
  }

  revokeInvite(id: number): Observable<VolunteerInvite> {
    return this.http.patch<VolunteerInvite>(`${this.base}/${id}/revoke`, {});
  }
}
