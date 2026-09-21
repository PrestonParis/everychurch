package com.everychurch.server.service;

import com.everychurch.server.dto.PresignedUploadResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit-level (mocked S3Presigner, no Spring context, no real AWS call) — a real
 * presign needs valid-looking AWS credentials the test environment doesn't have, but
 * the object-key/URL-building logic this class owns doesn't depend on that at all.
 */
@ExtendWith(MockitoExtension.class)
class S3PresignServiceTest {

    @Mock S3Presigner presigner;
    @Mock PresignedPutObjectRequest presignedRequest;

    @Test
    void presignPutReturnsAWellFormedUrlWithTheRightKeyPrefix() throws Exception {
        when(presignedRequest.url()).thenReturn(
            URI.create("https://example-bucket.s3.amazonaws.com/church-images/42/some-uuid.jpg?X-Amz-Signature=abc").toURL());
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

        S3PresignService service = new S3PresignService(presigner, "example-bucket", "us-east-1");

        PresignedUploadResponse response = service.presignPut("church-images/42", "image/jpeg", Duration.ofMinutes(5));

        assertTrue(response.objectKey().startsWith("church-images/42/"));
        assertTrue(response.objectKey().endsWith(".jpg"), "image/jpeg should map to a .jpg extension");
        assertEquals("https://example-bucket.s3.us-east-1.amazonaws.com/" + response.objectKey(), response.publicUrl());
        assertNotNull(response.uploadUrl());
    }

    @Test
    void passesThroughAnUnmappedContentTypeSubtypeAsTheExtension() throws Exception {
        when(presignedRequest.url()).thenReturn(URI.create("https://example-bucket.s3.amazonaws.com/x").toURL());
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

        S3PresignService service = new S3PresignService(presigner, "example-bucket", "us-east-1");
        PresignedUploadResponse response = service.presignPut("church-sermons/7", "audio/mp4", Duration.ofMinutes(15));

        assertTrue(response.objectKey().endsWith(".mp4"));
    }

    @Test
    void fallsBackToABinExtensionForAMalformedContentType() throws Exception {
        when(presignedRequest.url()).thenReturn(URI.create("https://example-bucket.s3.amazonaws.com/x").toURL());
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

        S3PresignService service = new S3PresignService(presigner, "example-bucket", "us-east-1");
        PresignedUploadResponse response = service.presignPut("church-images/1", null, Duration.ofMinutes(5));

        assertTrue(response.objectKey().endsWith(".bin"));
    }

    @Test
    void throwsAClearErrorWhenNoBucketIsConfigured() {
        S3PresignService service = new S3PresignService(presigner, "", "us-east-1");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.presignPut("church-images/1", "image/png", Duration.ofMinutes(5)));
        assertTrue(ex.getMessage().contains("S3_BUCKET_NAME"));
    }

    @Test
    void wrapsPresignerFailuresInAClearError() {
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenThrow(new RuntimeException("no credentials"));

        S3PresignService service = new S3PresignService(presigner, "example-bucket", "us-east-1");

        assertThrows(IllegalStateException.class,
            () -> service.presignPut("church-images/1", "image/png", Duration.ofMinutes(5)));
    }
}
