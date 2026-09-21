package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;

import com.everychurch.server.dto.LoginRequest;
import com.everychurch.server.dto.RegisterRequest;
import com.everychurch.server.dto.VolunteerRegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end: an admin generates a volunteer invite, a recruit redeems it into a
 * Role.VOLUNTEER account, and the code can't be reused. Uses the seeded Platform
 * Admin (admin.seed.email/password in test application.properties) rather than
 * creating one, since nothing else can grant PLATFORM_ADMIN.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VolunteerInviteFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String adminToken() throws Exception {
        LoginRequest login = new LoginRequest("admin@everychurch.local", "test-admin-password-123");
        String body = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(login)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    @Test
    void nonAdminCannotGenerateInvites() throws Exception {
        RegisterRequest register = new RegisterRequest("regularmember@example.com", "password123");
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(register)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String memberToken = objectMapper.readTree(body).get("token").asText();

        mockMvc.perform(post("/api/admin/volunteer-invites")
                .header("Authorization", "Bearer " + memberToken)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotGenerateInvites() throws Exception {
        mockMvc.perform(post("/api/admin/volunteer-invites")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminGeneratesInvite_volunteerRedeemsIt_codeCannotBeReused() throws Exception {
        String token = adminToken();

        String createBody = mockMvc.perform(post("/api/admin/volunteer-invites")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn().getResponse().getContentAsString();

        String code = objectMapper.readTree(createBody).get("code").asText();

        VolunteerRegisterRequest volunteerRegister = new VolunteerRegisterRequest(code, "recruit@example.com", "password123");

        mockMvc.perform(post("/api/auth/register/volunteer")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(volunteerRegister)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.role").value("VOLUNTEER"));

        // same code, different recruit — must be rejected
        VolunteerRegisterRequest reuse = new VolunteerRegisterRequest(code, "someoneelse@example.com", "password123");

        mockMvc.perform(post("/api/auth/register/volunteer")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(reuse)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCodeIsRejected() throws Exception {
        VolunteerRegisterRequest request = new VolunteerRegisterRequest("NOT-A-REAL-CODE", "nobody@example.com", "password123");

        mockMvc.perform(post("/api/auth/register/volunteer")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void revokedCodeIsRejected() throws Exception {
        String token = adminToken();

        String createBody = mockMvc.perform(post("/api/admin/volunteer-invites")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        JsonNode created = objectMapper.readTree(createBody);
        long id = created.get("id").asLong();
        String code = created.get("code").asText();

        mockMvc.perform(patch("/api/admin/volunteer-invites/" + id + "/revoke")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REVOKED"));

        VolunteerRegisterRequest request = new VolunteerRegisterRequest(code, "toolate@example.com", "password123");

        mockMvc.perform(post("/api/auth/register/volunteer")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanListInvites() throws Exception {
        String token = adminToken();

        mockMvc.perform(get("/api/admin/volunteer-invites")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }
}
