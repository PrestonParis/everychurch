package com.everychurch.server.service;

import com.everychurch.server.dto.PresignedUploadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Generates presigned S3 PUT URLs so a client uploads a file directly to S3 — never
 * through this server, so large files don't touch API memory/bandwidth (see
 * docs/ARCHITECTURE.md "Object Storage"). Shared by ChurchImageService and
 * SermonService rather than duplicated per feature.
 */
@Service
public class S3PresignService {

    // image/jpeg -> jpg, audio/mpeg -> mp3; every other subtype (png, gif, webp,
    // mp4, webm, ...) is already a sane file extension as-is.
    private static final Map<String, String> EXTENSION_OVERRIDES = Map.of(
        "jpeg", "jpg",
        "mpeg", "mp3"
    );

    private final S3Presigner presigner;
    private final String bucketName;
    private final String region;

    public S3PresignService(
        S3Presigner presigner,
        @Value("${aws.s3.bucket-name:}") String bucketName,
        @Value("${aws.region:us-east-1}") String region
    ) {
        this.presigner = presigner;
        this.bucketName = bucketName;
        this.region = region;
    }

    /**
     * @param keyPrefix e.g. "church-images/42" — the object key becomes
     *                  "{keyPrefix}/{uuid}.{ext}".
     */
    public PresignedUploadResponse presignPut(String keyPrefix, String contentType, Duration expiry) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalStateException("Uploads aren't configured — set S3_BUCKET_NAME to enable this feature");
        }

        String objectKey = keyPrefix + "/" + UUID.randomUUID() + "." + extensionFor(contentType);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(objectKey)
            .contentType(contentType)
            .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(expiry)
            .putObjectRequest(putObjectRequest)
            .build();

        try {
            PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
            return new PresignedUploadResponse(presigned.url().toString(), objectKey, publicUrl(objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate an upload URL — check AWS credentials are configured: " + e.getMessage(), e);
        }
    }

    private String publicUrl(String objectKey) {
        return "https://" + bucketName + ".s3." + region + ".amazonaws.com/" + objectKey;
    }

    private String extensionFor(String contentType) {
        if (contentType == null || !contentType.contains("/")) {
            return "bin";
        }
        String subtype = contentType.substring(contentType.indexOf('/') + 1).toLowerCase();
        return EXTENSION_OVERRIDES.getOrDefault(subtype, subtype);
    }
}
