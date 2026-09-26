import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Church } from '../models/church.model';

@Injectable({ providedIn: 'root' })
export class FavoriteService {
  private http = inject(HttpClient);

  status(churchId: number): Observable<{ favorited: boolean }> {
    return this.http.get<{ favorited: boolean }>(`/api/churches/${churchId}/favorite`);
  }

  favorite(churchId: number): Observable<void> {
    return this.http.post<void>(`/api/churches/${churchId}/favorite`, {});
  }

  unfavorite(churchId: number): Observable<void> {
    return this.http.delete<void>(`/api/churches/${churchId}/favorite`);
  }

  myFavorites(): Observable<Church[]> {
    return this.http.get<Church[]>('/api/users/me/favorites');
  }

  /** Feeds the Favorites page's empty state — see FavoriteService.suggestions on the
   *  server for the denomination-matching heuristic. */
  suggestions(): Observable<Church[]> {
    return this.http.get<Church[]>('/api/users/me/favorite-suggestions');
  }

  recentlyViewed(limit?: number): Observable<Church[]> {
    const params = limit !== undefined ? { limit } : undefined;
    return this.http.get<Church[]>('/api/users/me/recently-viewed', { params });
  }
}
