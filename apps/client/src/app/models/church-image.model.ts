export interface ChurchImage {
  id: number;
  churchId: number;
  url: string;
  sortOrder: number;
}

/**
 * Response from POST /api/churches/{churchId}/images/upload-url (Prompt 8).
 * `publicUrl` is not literally spelled out in Prompt 8's contract, but its own
 * text says the URL format should "live in one place" server-side — returning
 * it here (rather than the client reconstructing an S3 URL from bucket/region
 * it has no business knowing) is how this UI honors that. Flag this to
 * whoever implements Prompt 8 if it isn't already included.
 */
export interface UploadUrlResponse {
  uploadUrl: string;
  objectKey: string;
  publicUrl: string;
}
