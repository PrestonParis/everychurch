import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChurchPermission, ChurchStaffSummary } from '../models/church-staff.model';

@Injectable({ providedIn: 'root' })
export class ChurchStaffService {
  private http = inject(HttpClient);

  /** Every church the current user staffs, across the whole app — powers "does this
   *  person have anything to manage" checks (EventListComponent/EventDetailComponent)
   *  without offering a button a plain Member could never actually use. */
  myStaffChurches(): Observable<ChurchStaffSummary[]> {
    return this.http.get<ChurchStaffSummary[]>('/api/users/me/staff-churches');
  }

  // One church's staff roster — the management screen (ChurchStaffController on the
  // server; requires MANAGE_STAFF or ownership there, same as everywhere else).
  list(churchId: number): Observable<ChurchStaffSummary[]> {
    return this.http.get<ChurchStaffSummary[]>(`/api/churches/${churchId}/staff`);
  }

  add(churchId: number, email: string, permissions: ChurchPermission[]): Observable<ChurchStaffSummary> {
    return this.http.post<ChurchStaffSummary>(`/api/churches/${churchId}/staff`, { email, permissions });
  }

  updatePermissions(churchId: number, staffId: number, permissions: ChurchPermission[]): Observable<ChurchStaffSummary> {
    return this.http.patch<ChurchStaffSummary>(`/api/churches/${churchId}/staff/${staffId}`, { permissions });
  }

  remove(churchId: number, staffId: number): Observable<void> {
    return this.http.delete<void>(`/api/churches/${churchId}/staff/${staffId}`);
  }
}

/** True if any of the given memberships carries `permission`. */
export function hasAnyChurchPermission(summaries: ChurchStaffSummary[], permission: ChurchPermission): boolean {
  return summaries.some((s) => s.permissions.includes(permission));
}
