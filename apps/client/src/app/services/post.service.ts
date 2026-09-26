import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Post } from '../models/post.model';

@Injectable({ providedIn: 'root' })
export class PostService {
  private http = inject(HttpClient);

  list(churchId: number, before?: number, limit?: number): Observable<Post[]> {
    let params = new HttpParams();
    if (before !== undefined) params = params.set('before', before);
    if (limit !== undefined) params = params.set('limit', limit);
    return this.http.get<Post[]>(`/api/churches/${churchId}/posts`, { params });
  }

  create(churchId: number, body: string): Observable<Post> {
    return this.http.post<Post>(`/api/churches/${churchId}/posts`, { body });
  }

  delete(churchId: number, postId: number): Observable<void> {
    return this.http.delete<void>(`/api/churches/${churchId}/posts/${postId}`);
  }

  /** Every post from every church the current user follows, newest first. */
  feed(before?: number, limit?: number): Observable<Post[]> {
    let params = new HttpParams();
    if (before !== undefined) params = params.set('before', before);
    if (limit !== undefined) params = params.set('limit', limit);
    return this.http.get<Post[]>('/api/users/me/feed', { params });
  }
}
