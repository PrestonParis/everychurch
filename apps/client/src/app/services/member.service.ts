import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Member, MembershipStatus } from '../models/member.model';

@Injectable({ providedIn: 'root' })
export class MemberService {
  private http = inject(HttpClient);

  private base(churchId: number): string {
    return `/api/churches/${churchId}/members`;
  }

  status(churchId: number): Observable<MembershipStatus> {
    return this.http.get<MembershipStatus>(`${this.base(churchId)}/me`);
  }

  list(churchId: number): Observable<Member[]> {
    return this.http.get<Member[]>(this.base(churchId));
  }

  join(churchId: number): Observable<Member> {
    return this.http.post<Member>(this.base(churchId), {});
  }

  leave(churchId: number): Observable<void> {
    return this.http.delete<void>(`${this.base(churchId)}/me`);
  }
}
