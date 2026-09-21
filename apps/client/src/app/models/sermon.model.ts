export interface Sermon {
  id?: number;
  churchId?: number;
  title: string;
  date: string; // ISO date, e.g. "2026-09-17" — matches <input type="date">
  mediaUrl?: string;
}

/** Response from POST /api/churches/{churchId}/sermons/upload-url (Prompt 9). */
export interface UploadUrlResponse {
  uploadUrl: string;
  objectKey: string;
  publicUrl: string;
}
