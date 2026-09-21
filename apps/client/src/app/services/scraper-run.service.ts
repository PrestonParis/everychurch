import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ScraperRun, TriggerScraperRunResult } from '../models/scraper-run.model';

@Injectable({ providedIn: 'root' })
export class ScraperRunService {
  private http = inject(HttpClient);
  private base = '/api/admin/scraper-runs';

  list(): Observable<ScraperRun[]> {
    return this.http.get<ScraperRun[]>(this.base);
  }

  trigger(region?: string): Observable<TriggerScraperRunResult> {
    return this.http.post<TriggerScraperRunResult>(`${this.base}/trigger`, { region: region || null });
  }
}
