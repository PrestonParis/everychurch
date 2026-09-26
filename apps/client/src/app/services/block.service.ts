import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class BlockService {
  private http = inject(HttpClient);

  status(userId: number): Observable<{ blocked: boolean }> {
    return this.http.get<{ blocked: boolean }>(`/api/users/${userId}/block`);
  }

  block(userId: number): Observable<void> {
    return this.http.post<void>(`/api/users/${userId}/block`, {});
  }

  unblock(userId: number): Observable<void> {
    return this.http.delete<void>(`/api/users/${userId}/block`);
  }
}
