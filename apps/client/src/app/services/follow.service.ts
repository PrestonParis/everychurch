import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Church } from '../models/church.model';

@Injectable({ providedIn: 'root' })
export class FollowService {
  private http = inject(HttpClient);

  status(churchId: number): Observable<{ following: boolean; followerCount: number }> {
    return this.http.get<{ following: boolean; followerCount: number }>(`/api/churches/${churchId}/follow`);
  }

  follow(churchId: number): Observable<void> {
    return this.http.post<void>(`/api/churches/${churchId}/follow`, {});
  }

  unfollow(churchId: number): Observable<void> {
    return this.http.delete<void>(`/api/churches/${churchId}/follow`);
  }

  myFollowing(): Observable<Church[]> {
    return this.http.get<Church[]>('/api/users/me/following');
  }
}
