import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';
import { PublicConfig } from '../models/public-config.model';

/** GET /api/config is public and returns the same values for the whole session, so
 *  one request is cached and shared with every subscriber (shareReplay(1)) rather
 *  than every church profile page re-fetching it. */
@Injectable({ providedIn: 'root' })
export class ConfigService {
  private http = inject(HttpClient);

  private readonly config$: Observable<PublicConfig> = this.http
    .get<PublicConfig>('/api/config')
    .pipe(shareReplay(1));

  get(): Observable<PublicConfig> {
    return this.config$;
  }
}
