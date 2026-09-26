import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { UploadUrlResponse } from '../models/church-image.model';
import { UserProfile } from '../models/user.model';

@Injectable({ providedIn: 'root' })
export class UserProfileService {
  private http = inject(HttpClient);
  private base = '/api/users/me';

  updateDisplayName(displayName: string): Observable<UserProfile> {
    return this.http.put<UserProfile>(`${this.base}/display-name`, { displayName });
  }

  /** Presigned S3 PUT URL, valid ~5 minutes. Not stored until updateAvatar() is called. */
  getAvatarUploadUrl(contentType: string): Observable<UploadUrlResponse> {
    return this.http.post<UploadUrlResponse>(`${this.base}/avatar/upload-url`, { contentType });
  }

  /** Uploads directly to S3 — never through the API server. */
  uploadToS3(uploadUrl: string, file: File): Observable<unknown> {
    return this.http.put(uploadUrl, file, { headers: { 'Content-Type': file.type } });
  }

  updateAvatar(url: string): Observable<UserProfile> {
    return this.http.put<UserProfile>(`${this.base}/avatar`, { url });
  }
}
