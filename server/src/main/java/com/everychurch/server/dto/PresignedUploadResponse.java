package com.everychurch.server.dto;

/**
 * uploadUrl: PUT the file body here directly, with a matching Content-Type header.
 * publicUrl: what to hand to the existing create endpoint (e.g. POST .../images)
 * once the PUT succeeds — the S3 URL format lives only in S3PresignService.
 */
public record PresignedUploadResponse(String uploadUrl, String objectKey, String publicUrl) {}
