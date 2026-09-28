import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { UploadUrlResponse } from '../models/church-image.model';
import { MyProfile, UpdatePrivacyRequest, UpdateProfileRequest, UserProfile } from '../models/user.model';

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

  /** Empty/blank url clears the avatar (Settings' "Remove photo"). */
  updateAvatar(url: string): Observable<UserProfile> {
    return this.http.put<UserProfile>(`${this.base}/avatar`, { url });
  }

  getMyProfile(): Observable<MyProfile> {
    return this.http.get<MyProfile>(this.base);
  }

  /** One consolidated save for the whole Profile tab. */
  updateProfile(request: UpdateProfileRequest): Observable<MyProfile> {
    return this.http.put<MyProfile>(`${this.base}/profile`, request);
  }

  updatePrivacy(request: UpdatePrivacyRequest): Observable<MyProfile> {
    return this.http.put<MyProfile>(`${this.base}/privacy`, request);
  }

  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.put<void>(`${this.base}/password`, { currentPassword, newPassword });
  }

  /** Backs the live-typing check in Settings' username field. */
  isUsernameAvailable(value: string): Observable<{ available: boolean }> {
    return this.http.get<{ available: boolean }>(`${this.base}/username-available`, { params: { value } });
  }
}
