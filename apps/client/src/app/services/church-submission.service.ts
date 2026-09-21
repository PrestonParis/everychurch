import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChurchSubmission, ChurchSubmissionStatus } from '../models/church-submission.model';

@Injectable({ providedIn: 'root' })
export class ChurchSubmissionService {
  private http = inject(HttpClient);
  private base = '/api/admin/submissions';

  list(status?: ChurchSubmissionStatus): Observable<ChurchSubmission[]> {
    return this.http.get<ChurchSubmission[]>(this.base, { params: status ? { status } : {} });
  }

  approve(id: number): Observable<ChurchSubmission> {
    return this.http.post<ChurchSubmission>(`${this.base}/${id}/approve`, {});
  }

  reject(id: number): Observable<ChurchSubmission> {
    return this.http.post<ChurchSubmission>(`${this.base}/${id}/reject`, {});
  }
}
