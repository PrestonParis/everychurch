import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Church, PagedResult } from '../models/church.model';

export interface ChurchSearchParams {
  query?: string;
  denomination?: string;
  state?: string;
  city?: string;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class ChurchService {
  private http = inject(HttpClient);
  private base = '/api/churches';

  search(params: ChurchSearchParams = {}): Observable<PagedResult<Church>> {
    let httpParams = new HttpParams()
      .set('page', String(params.page ?? 0))
      .set('size', String(params.size ?? 20));

    if (params.query) httpParams = httpParams.set('query', params.query);
    if (params.denomination) httpParams = httpParams.set('denomination', params.denomination);
    if (params.state) httpParams = httpParams.set('state', params.state);
    if (params.city) httpParams = httpParams.set('city', params.city);

    return this.http.get<PagedResult<Church>>(this.base, { params: httpParams });
  }

  nearest(lat: number, lng: number, radiusMeters?: number, limit?: number): Observable<Church[]> {
    let httpParams = new HttpParams().set('lat', String(lat)).set('lng', String(lng));
    if (radiusMeters !== undefined) httpParams = httpParams.set('radiusMeters', String(radiusMeters));
    if (limit !== undefined) httpParams = httpParams.set('limit', String(limit));

    return this.http.get<Church[]>(`${this.base}/nearest`, { params: httpParams });
  }

  getById(id: number): Observable<Church> {
    return this.http.get<Church>(`${this.base}/${id}`);
  }

  create(church: Church): Observable<Church> {
    return this.http.post<Church>(this.base, church);
  }

  update(id: number, church: Church): Observable<Church> {
    return this.http.put<Church>(`${this.base}/${id}`, church);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  parseUrl(url: string): Observable<Church> {
    return this.http.post<Church>(`${this.base}/parse-url`, { url });
  }
}
