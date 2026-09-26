package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.dto.LoginRequest;
import com.everychurch.server.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST .../images/upload-url and .../sermons/upload-url (Prompts 8-9): the exact same
 * ownership/permission gate as the rest of that resource (ChurchImageController /
 * SermonController), reached before S3PresignService ever runs. No S3_BUCKET_NAME is
 * configured in test properties on purpose (see application.properties), so a
 * permitted caller still gets a 503 rather than a well-formed URL here — the "well-
 * formed URL" happy path is covered by S3PresignServiceTest against a mocked
 * S3Presigner instead, since a real presign needs real-looking AWS credentials this
 * environment doesn't have.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UploadUrlPermissionsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final AtomicInteger counter = new AtomicInteger();

    private String register(String email) throws Exception {
        RegisterRequest request = new RegisterRequest(email, "password123", "Test User");
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String adminToken() throws Exception {
        LoginRequest login = new LoginRequest("admin@everychurch.local", "test-admin-password-123");
        String body = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(login)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private long createChurch(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Owner of a CLAIMED church — images/sermons upload-url reuses the claimed-church EDIT_PROFILE/MANAGE_SERMONS gate. */
    private record ClaimedChurch(long churchId, String ownerToken) {}

    private ClaimedChurch claimedChurch(String uniqueSuffix) throws Exception {
        String ownerToken = register("upload-owner" + uniqueSuffix + "@example.com");
        long churchId = createChurch(ownerToken, "Upload Fixture Church " + uniqueSuffix);

        String claimBody = mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long claimId = objectMapper.readTree(claimBody).get("id").asLong();

        mockMvc.perform(post("/api/admin/claims/" + claimId + "/approve")
                .header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isOk());

        return new ClaimedChurch(churchId, ownerToken);
    }

    @Test
    void ownerGetsAConfigurationErrorNotAPermissionErrorForImages() throws Exception {
        ClaimedChurch church = claimedChurch("img" + counter.incrementAndGet());

        // permission check passes; S3PresignService then reports "not configured" —
        // proves the gate is permission-first, config-second, and never crashes.
        mockMvc.perform(post("/api/churches/" + church.churchId() + "/images/upload-url")
                .header("Authorization", "Bearer " + church.ownerToken())
                .contentType("application/json")
                .content("{\"contentType\":\"image/jpeg\"}"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void strangerCannotGetAnImageUploadUrl() throws Exception {
        ClaimedChurch church = claimedChurch("img" + counter.incrementAndGet());
        String stranger = register("upload-stranger-img" + counter.incrementAndGet() + "@example.com");

        mockMvc.perform(post("/api/churches/" + church.churchId() + "/images/upload-url")
                .header("Authorization", "Bearer " + stranger)
                .contentType("application/json")
                .content("{\"contentType\":\"image/jpeg\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotGetAnImageUploadUrl() throws Exception {
        ClaimedChurch church = claimedChurch("img" + counter.incrementAndGet());

        mockMvc.perform(post("/api/churches/" + church.churchId() + "/images/upload-url")
                .contentType("application/json")
                .content("{\"contentType\":\"image/jpeg\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void platformAdminBypassesOwnershipButStillHitsTheConfigurationError() throws Exception {
        ClaimedChurch church = claimedChurch("img" + counter.incrementAndGet());

        mockMvc.perform(post("/api/churches/" + church.churchId() + "/images/upload-url")
                .header("Authorization", "Bearer " + adminToken())
                .contentType("application/json")
                .content("{\"contentType\":\"image/jpeg\"}"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void ownerGetsAConfigurationErrorNotAPermissionErrorForSermons() throws Exception {
        ClaimedChurch church = claimedChurch("sermon" + counter.incrementAndGet());

        mockMvc.perform(post("/api/churches/" + church.churchId() + "/sermons/upload-url")
                .header("Authorization", "Bearer " + church.ownerToken())
                .contentType("application/json")
                .content("{\"contentType\":\"audio/mpeg\"}"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void strangerCannotGetASermonUploadUrl() throws Exception {
        ClaimedChurch church = claimedChurch("sermon" + counter.incrementAndGet());
        String stranger = register("upload-stranger-sermon" + counter.incrementAndGet() + "@example.com");

        mockMvc.perform(post("/api/churches/" + church.churchId() + "/sermons/upload-url")
                .header("Authorization", "Bearer " + stranger)
                .contentType("application/json")
                .content("{\"contentType\":\"audio/mpeg\"}"))
            .andExpect(status().isForbidden());
    }
}
