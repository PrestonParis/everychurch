import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Report, ReportStatus, ReportTargetType } from '../models/report.model';

@Injectable({ providedIn: 'root' })
export class ReportService {
  private http = inject(HttpClient);

  create(targetType: ReportTargetType, targetId: number, reason: string): Observable<Report> {
    return this.http.post<Report>('/api/reports', { targetType, targetId, reason });
  }

  // Admin-only (server-enforced) — the review queue.
  list(status?: ReportStatus): Observable<Report[]> {
    const params = status ? new HttpParams().set('status', status) : undefined;
    return this.http.get<Report[]>('/api/admin/reports', { params });
  }

  resolve(id: number, status: 'ACTIONED' | 'DISMISSED'): Observable<Report> {
    return this.http.patch<Report>(`/api/admin/reports/${id}`, { status });
  }
}
