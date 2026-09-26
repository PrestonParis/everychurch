package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PUT /api/users/me/display-name, POST /api/users/me/avatar/upload-url, PUT
 *  /api/users/me/avatar (the new profile feature) — display-name/avatar changes
 *  persist and are reflected in a subsequent login's AuthResponse, since the client
 *  has no other way to refetch identity (see UserProfileService, AuthService
 *  .toAuthResponse). No S3_BUCKET_NAME is configured for this test environment (see
 *  S3PresignServiceTest), so avatarUploadUrl is expected to fail with 503. */
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String authToken(String email) throws Exception {
        RegisterRequest request = new RegisterRequest(email, "password123", "Original Name");
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    @Test
    void updateDisplayName_persistsAndReflectsInLogin() throws Exception {
        String token = authToken("displayname-fixture@example.com");

        mockMvc.perform(put("/api/users/me/display-name")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"displayName\":\"New Name\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("New Name"));

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"displayname-fixture@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("New Name"));
    }

    @Test
    void updateDisplayName_blank_badRequest() throws Exception {
        String token = authToken("blankname-fixture@example.com");

        mockMvc.perform(put("/api/users/me/display-name")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"displayName\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateAvatar_persistsAndReflectsInLogin() throws Exception {
        String token = authToken("avatar-fixture@example.com");
        String avatarUrl = "https://example-bucket.s3.us-east-1.amazonaws.com/avatars/1/abc.jpg";

        mockMvc.perform(put("/api/users/me/avatar")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"url\":\"" + avatarUrl + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.avatarUrl").value(avatarUrl));

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"avatar-fixture@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.avatarUrl").value(avatarUrl));
    }

    @Test
    void avatarUploadUrl_withoutS3Configured_serviceUnavailable() throws Exception {
        String token = authToken("upload-fixture@example.com");

        mockMvc.perform(post("/api/users/me/avatar/upload-url")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"contentType\":\"image/png\"}"))
            .andExpect(status().isServiceUnavailable());
    }
}
