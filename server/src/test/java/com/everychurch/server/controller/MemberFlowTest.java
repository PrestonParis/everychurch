package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.dto.LoginRequest;
import com.everychurch.server.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Prompt 13: congregational membership, and the rule that only a church's own people can see its member list. */
@SpringBootTest
@AutoConfigureMockMvc
class MemberFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, "password123"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new LoginRequest("admin@everychurch.local", "test-admin-password-123"))))
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

    private void join(String token, long churchId) throws Exception {
        mockMvc.perform(post("/api/churches/" + churchId + "/members")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated());
    }

    @Test
    void joinListAndLeave() throws Exception {
        String email = uniqueEmail("alice");
        String alice = register(email);
        long churchId = createChurch(alice, "Member Test Church");

        mockMvc.perform(post("/api/churches/" + churchId + "/members")
                .header("Authorization", "Bearer " + alice))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.churchId").value(churchId))
            // the local part of the email, never the full address
            .andExpect(jsonPath("$.displayName").value(email.substring(0, email.indexOf('@'))));

        mockMvc.perform(get("/api/churches/" + churchId + "/members")
                .header("Authorization", "Bearer " + alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].email").doesNotExist());

        mockMvc.perform(delete("/api/churches/" + churchId + "/members/me")
                .header("Authorization", "Bearer " + alice))
            .andExpect(status().isNoContent());

        // no longer a member, so no longer allowed to see the list
        mockMvc.perform(get("/api/churches/" + churchId + "/members")
                .header("Authorization", "Bearer " + alice))
            .andExpect(status().isForbidden());
    }

    @Test
    void joiningTwiceIsAConflict() throws Exception {
        String token = register(uniqueEmail("dup"));
        long churchId = createChurch(token, "Dup Join Church");
        join(token, churchId);

        mockMvc.perform(post("/api/churches/" + churchId + "/members")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());
    }

    @Test
    void leavingWithoutBeingAMemberIsNotFound() throws Exception {
        String token = register(uniqueEmail("never"));
        long churchId = createChurch(token, "Never Joined Church");

        mockMvc.perform(delete("/api/churches/" + churchId + "/members/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
    }

    @Test
    void joiningAChurchThatDoesNotExistIsNotFound() throws Exception {
        String token = register(uniqueEmail("ghost"));

        mockMvc.perform(post("/api/churches/999999999/members")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
    }

    @Test
    void aMemberOfOneChurchCannotListAnothersMembers() throws Exception {
        String memberA = register(uniqueEmail("membera"));
        String memberB = register(uniqueEmail("memberb"));
        long churchA = createChurch(memberA, "Church A");
        long churchB = createChurch(memberB, "Church B");
        join(memberA, churchA);
        join(memberB, churchB);

        // 403, not an empty list: "not allowed" is different from "nobody here"
        mockMvc.perform(get("/api/churches/" + churchB + "/members")
                .header("Authorization", "Bearer " + memberA))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/churches/" + churchA + "/members")
                .header("Authorization", "Bearer " + memberA))
            .andExpect(status().isOk());
    }

    @Test
    void churchStaffAndPlatformAdminCanListWithoutBeingMembers() throws Exception {
        String owner = register(uniqueEmail("owner"));
        long churchId = createChurch(owner, "Staff Access Church");

        String claimBody = mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + owner)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long claimId = objectMapper.readTree(claimBody).get("id").asLong();

        String admin = adminToken();
        mockMvc.perform(post("/api/admin/claims/" + claimId + "/approve")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk());

        // the owner is staff but never joined as a member
        mockMvc.perform(get("/api/churches/" + churchId + "/members")
                .header("Authorization", "Bearer " + owner))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/churches/" + churchId + "/members")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk());
    }

    @Test
    void statusReportsWhatTheUserIsToTheChurch() throws Exception {
        String token = register(uniqueEmail("status"));
        long churchId = createChurch(token, "Status Church");

        mockMvc.perform(get("/api/churches/" + churchId + "/members/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.member").value(false))
            .andExpect(jsonPath("$.staff").value(false))
            .andExpect(jsonPath("$.canChat").value(false));

        join(token, churchId);

        mockMvc.perform(get("/api/churches/" + churchId + "/members/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$.member").value(true))
            .andExpect(jsonPath("$.canChat").value(true));
    }

    @Test
    void privateSubresourcesRejectAnonymousCallersWith401() throws Exception {
        String token = register(uniqueEmail("anon"));
        long churchId = createChurch(token, "Anon Church");

        mockMvc.perform(get("/api/churches/" + churchId + "/members")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/churches/" + churchId + "/members/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/churches/" + churchId + "/members")).andExpect(status().isUnauthorized());
        // the staff list was previously reachable anonymously and blew up with a 500
        mockMvc.perform(get("/api/churches/" + churchId + "/staff")).andExpect(status().isUnauthorized());
        // ...while the public church profile stays public
        mockMvc.perform(get("/api/churches/" + churchId)).andExpect(status().isOk());
    }
}
