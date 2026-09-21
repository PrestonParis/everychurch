import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChurchImage, UploadUrlResponse } from '../models/church-image.model';

@Injectable({ providedIn: 'root' })
export class ChurchImageService {
  private http = inject(HttpClient);

  private base(churchId: number): string {
    return `/api/churches/${churchId}/images`;
  }

  list(churchId: number): Observable<ChurchImage[]> {
    return this.http.get<ChurchImage[]>(this.base(churchId));
  }

  /** See Prompt 8: presigned S3 PUT URL, valid ~5 minutes. Not stored until `create` is called. */
  getUploadUrl(churchId: number, contentType: string): Observable<UploadUrlResponse> {
    return this.http.post<UploadUrlResponse>(`${this.base(churchId)}/upload-url`, { contentType });
  }

  /** Uploads directly to S3 — never through the API server, so large files don't touch it. */
  uploadToS3(uploadUrl: string, file: File): Observable<unknown> {
    return this.http.put(uploadUrl, file, { headers: { 'Content-Type': file.type } });
  }

  create(churchId: number, url: string, sortOrder: number): Observable<ChurchImage> {
    return this.http.post<ChurchImage>(this.base(churchId), { url, sortOrder });
  }

  updatePosition(churchId: number, imageId: number, url: string, sortOrder: number): Observable<ChurchImage> {
    return this.http.put<ChurchImage>(`${this.base(churchId)}/${imageId}`, { url, sortOrder });
  }

  delete(churchId: number, imageId: number): Observable<void> {
    return this.http.delete<void>(`${this.base(churchId)}/${imageId}`);
  }
}
