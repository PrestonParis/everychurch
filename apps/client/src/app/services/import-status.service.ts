import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ImportStatus } from '../models/import-status.model';

/** GET /api/import-status — deliberately not cached (see ImportStatus's own comment):
 *  the church-list cold-start onboarding screen polls this repeatedly. */
@Injectable({ providedIn: 'root' })
export class ImportStatusService {
  private http = inject(HttpClient);

  get(): Observable<ImportStatus> {
    return this.http.get<ImportStatus>('/api/import-status');
  }
}
