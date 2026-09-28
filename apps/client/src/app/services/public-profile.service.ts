import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PublicUserProfile } from '../models/user.model';

@Injectable({ providedIn: 'root' })
export class PublicProfileService {
  private http = inject(HttpClient);

  get(username: string): Observable<PublicUserProfile> {
    return this.http.get<PublicUserProfile>(`/api/users/${encodeURIComponent(username)}`);
  }
}
