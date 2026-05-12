import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, tap } from 'rxjs';
import { Church } from '../models/church.model';

@Injectable({ providedIn: 'root' })
export class ChurchService {
  private http = inject(HttpClient);
  private base = 'http://localhost:8080/api/churches';

  private cache: Church[] | null = null;

  getAll(): Observable<Church[]> {
    if (this.cache !== null) {
      return of(this.cache);
    }
    return this.http.get<Church[]>(this.base).pipe(
      tap((churches) => (this.cache = churches))
    );
  }

  getById(id: number): Observable<Church> {
    return this.http.get<Church>(`${this.base}/${id}`);
  }

  create(church: Church): Observable<Church> {
    return this.http.post<Church>(this.base, church).pipe(
      tap(() => (this.cache = null))
    );
  }

  update(id: number, church: Church): Observable<Church> {
    return this.http.put<Church>(`${this.base}/${id}`, church).pipe(
      tap(() => (this.cache = null))
    );
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`).pipe(
      tap(() => (this.cache = null))
    );
  }

  parseUrl(url: string): Observable<Church> {
    return this.http.post<Church>(`${this.base}/parse-url`, { url });
  }
}
