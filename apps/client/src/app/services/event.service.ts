import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChurchEvent } from '../models/event.model';

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
}
