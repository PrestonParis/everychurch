import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Sermon, UploadUrlResponse } from '../models/sermon.model';

@Injectable({ providedIn: 'root' })
export class SermonService {
  private http = inject(HttpClient);

  private base(churchId: number): string {
    return `/api/churches/${churchId}/sermons`;
  }

  list(churchId: number): Observable<Sermon[]> {
    return this.http.get<Sermon[]>(this.base(churchId));
  }

  /** See Prompt 9: presigned S3 PUT URL, ~15 minute expiry (audio/video is bigger/slower than images). */
  getUploadUrl(churchId: number, contentType: string): Observable<UploadUrlResponse> {
    return this.http.post<UploadUrlResponse>(`${this.base(churchId)}/upload-url`, { contentType });
  }

  uploadToS3(uploadUrl: string, file: File): Observable<unknown> {
    return this.http.put(uploadUrl, file, { headers: { 'Content-Type': file.type } });
  }

  create(churchId: number, sermon: Sermon): Observable<Sermon> {
    return this.http.post<Sermon>(this.base(churchId), sermon);
  }

  update(churchId: number, sermonId: number, sermon: Sermon): Observable<Sermon> {
    return this.http.put<Sermon>(`${this.base(churchId)}/${sermonId}`, sermon);
  }

  delete(churchId: number, sermonId: number): Observable<void> {
    return this.http.delete<void>(`${this.base(churchId)}/${sermonId}`);
  }
}
