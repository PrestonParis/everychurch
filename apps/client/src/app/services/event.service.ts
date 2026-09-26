import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChurchEvent } from '../models/event.model';
import { CoHostInvitation } from '../models/co-host-invitation.model';

@Injectable({ providedIn: 'root' })
export class EventService {
  private http = inject(HttpClient);
  private base = '/api/events';

  list(churchId?: number): Observable<ChurchEvent[]> {
    const params = churchId ? new HttpParams().set('churchId', String(churchId)) : undefined;
    return this.http.get<ChurchEvent[]>(this.base, { params });
  }

  get(id: number): Observable<ChurchEvent> {
    return this.http.get<ChurchEvent>(`${this.base}/${id}`);
  }

  create(event: ChurchEvent): Observable<ChurchEvent> {
    return this.http.post<ChurchEvent>(this.base, event);
  }

  update(id: number, event: ChurchEvent): Observable<ChurchEvent> {
    return this.http.put<ChurchEvent>(`${this.base}/${id}`, event);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  // Co-host consent (EventCoHost) — only someone with MANAGE_EVENTS on churchId
  // itself may accept/decline on its behalf; the server enforces this regardless of
  // who the UI lets click the button.
  acceptCoHost(eventId: number, churchId: number): Observable<ChurchEvent> {
    return this.http.post<ChurchEvent>(`${this.base}/${eventId}/co-hosts/${churchId}/accept`, {});
  }

  declineCoHost(eventId: number, churchId: number): Observable<ChurchEvent> {
    return this.http.post<ChurchEvent>(`${this.base}/${eventId}/co-hosts/${churchId}/decline`, {});
  }

  /** Outstanding "will your church co-host this event?" asks against a church the
   *  caller manages. */
  myCoHostInvitations(): Observable<CoHostInvitation[]> {
    return this.http.get<CoHostInvitation[]>('/api/users/me/co-host-invitations');
  }
}
