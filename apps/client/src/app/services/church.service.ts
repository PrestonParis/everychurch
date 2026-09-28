import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, Subject } from 'rxjs';
import { Church, PagedResult } from '../models/church.model';

export interface ChurchSearchParams {
  query?: string;
  denomination?: string;
  state?: string;
  city?: string;
  /** Per-browser-session value giving a stable pseudo-random default order; ignored server-side once `query` is set. */
  seed?: string;
  /** User's known location, if already silently available (see church-list.component.ts) — sorts nearest-first instead of shuffled. */
  lat?: number;
  lng?: number;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class ChurchService {
  private http = inject(HttpClient);
  private base = '/api/churches';

  /** Clicking "Browse" (app.ts) while already on the directory page is a same-URL
   *  routerLink navigation, which the Router silently ignores by default — no
   *  NavigationEnd, no component recreation, so any active search/filters just sit
   *  there looking like the click did nothing. ChurchListComponent listens on this
   *  to reset itself directly instead, since there's no routed-component recreation
   *  to hook into. */
  private readonly resetBrowseSubject = new Subject<void>();
  readonly resetBrowse$ = this.resetBrowseSubject.asObservable();
  resetBrowse() {
    this.resetBrowseSubject.next();
  }

  search(params: ChurchSearchParams = {}): Observable<PagedResult<Church>> {
    let httpParams = new HttpParams()
      .set('page', String(params.page ?? 0))
      .set('size', String(params.size ?? 20));

    if (params.query) httpParams = httpParams.set('query', params.query);
    if (params.denomination) httpParams = httpParams.set('denomination', params.denomination);
    if (params.state) httpParams = httpParams.set('state', params.state);
    if (params.city) httpParams = httpParams.set('city', params.city);
    if (params.seed) httpParams = httpParams.set('seed', params.seed);
    if (params.lat !== undefined) httpParams = httpParams.set('lat', String(params.lat));
    if (params.lng !== undefined) httpParams = httpParams.set('lng', String(params.lng));

    return this.http.get<PagedResult<Church>>(this.base, { params: httpParams });
  }

  suggestCities(prefix: string, state?: string): Observable<string[]> {
    let httpParams = new HttpParams().set('prefix', prefix);
    if (state) httpParams = httpParams.set('state', state);
    return this.http.get<string[]>(`${this.base}/cities`, { params: httpParams });
  }

  suggestStates(prefix: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/states`, { params: new HttpParams().set('prefix', prefix) });
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

  /** Fire-and-forget from ChurchProfileComponent — feeds "recently viewed" on the
   *  Favorites page. The server silently no-ops for anonymous callers (auth
   *  required), so this is only worth calling when the viewer is logged in. */
  recordView(id: number): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/view`, {});
  }
}
