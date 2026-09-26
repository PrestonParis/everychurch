package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.dto.RegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Church posts (write side gated to MANAGE_POSTS/PLATFORM_ADMIN, read side public)
 *  and the follow feed that surfaces them. */
@SpringBootTest
@AutoConfigureMockMvc
class PostFeedFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final AtomicInteger userCounter = new AtomicInteger();

    private String register(String prefix) throws Exception {
        RegisterRequest request = new RegisterRequest(
            prefix + "-" + userCounter.incrementAndGet() + "@example.com", "password123", "Test User");
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private long createChurch(String ownerToken, String name) throws Exception {
        String body = mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("name", name))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Claims the church so ownerToken's user becomes its owner (full permissions,
     *  including the new MANAGE_POSTS) — same flow ClaimAndStaffFlowTest uses. */
    private void claim(String ownerToken, long churchId) throws Exception {
        String claimBody = mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{}"))
            .andReturn().getResponse().getContentAsString();
        long claimId = objectMapper.readTree(claimBody).get("id").asLong();

        String admin = "Bearer " + loginAdmin();
        mockMvc.perform(post("/api/admin/claims/" + claimId + "/approve").header("Authorization", admin))
            .andExpect(status().isOk());
    }

    private String loginAdmin() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"admin@everychurch.local\",\"password\":\"test-admin-password-123\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    @Test
    void ownerCanPostAndAnyoneCanReadThePublicPostList() throws Exception {
        String ownerToken = register("post-owner");
        long churchId = createChurch(ownerToken, "Post Fixture Church");
        claim(ownerToken, churchId);

        mockMvc.perform(post("/api/churches/" + churchId + "/posts")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"body\":\"  Join us Sunday!  \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.body").value("Join us Sunday!"))
            .andExpect(jsonPath("$.churchId").value(churchId))
            .andExpect(jsonPath("$.churchName").value("Post Fixture Church"));

        // no auth needed to read
        mockMvc.perform(get("/api/churches/" + churchId + "/posts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void nonStaffCannotPostAndBlankOrOversizePostsAreRejected() throws Exception {
        String ownerToken = register("post-owner2");
        long churchId = createChurch(ownerToken, "Post Permission Fixture Church");
        claim(ownerToken, churchId);
        String strangerToken = register("post-stranger");

        mockMvc.perform(post("/api/churches/" + churchId + "/posts")
                .header("Authorization", "Bearer " + strangerToken)
                .contentType("application/json")
                .content("{\"body\":\"not allowed\"}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/churches/" + churchId + "/posts")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"body\":\"   \"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/churches/" + churchId + "/posts")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("body", "x".repeat(5001)))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ownerCanDeleteAPostButAStrangerCannot() throws Exception {
        String ownerToken = register("post-owner3");
        long churchId = createChurch(ownerToken, "Post Delete Fixture Church");
        claim(ownerToken, churchId);
        String strangerToken = register("post-stranger2");

        String postBody = mockMvc.perform(post("/api/churches/" + churchId + "/posts")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"body\":\"to be deleted\"}"))
            .andReturn().getResponse().getContentAsString();
        long postId = objectMapper.readTree(postBody).get("id").asLong();

        mockMvc.perform(delete("/api/churches/" + churchId + "/posts/" + postId)
                .header("Authorization", "Bearer " + strangerToken))
            .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/churches/" + churchId + "/posts/" + postId)
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/churches/" + churchId + "/posts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void feedShowsOnlyPostsFromFollowedChurchesNewestFirst() throws Exception {
        String ownerAToken = register("feed-owner-a");
        String ownerBToken = register("feed-owner-b");
        long churchA = createChurch(ownerAToken, "Feed Fixture Church A");
        long churchB = createChurch(ownerBToken, "Feed Fixture Church B");
        claim(ownerAToken, churchA);
        claim(ownerBToken, churchB);

        mockMvc.perform(post("/api/churches/" + churchA + "/posts")
                .header("Authorization", "Bearer " + ownerAToken)
                .contentType("application/json").content("{\"body\":\"A post 1\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/churches/" + churchB + "/posts")
                .header("Authorization", "Bearer " + ownerBToken)
                .contentType("application/json").content("{\"body\":\"B post 1 (never followed)\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/churches/" + churchA + "/posts")
                .header("Authorization", "Bearer " + ownerAToken)
                .contentType("application/json").content("{\"body\":\"A post 2\"}"))
            .andExpect(status().isCreated());

        String readerToken = register("feed-reader");
        String readerAuth = "Bearer " + readerToken;
        mockMvc.perform(post("/api/churches/" + churchA + "/follow").header("Authorization", readerAuth))
            .andExpect(status().isCreated());

        String body = mockMvc.perform(get("/api/users/me/feed").header("Authorization", readerAuth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode feed = objectMapper.readTree(body);

        assertEquals(2, feed.size(), "only the followed church's posts appear");
        assertEquals("A post 2", feed.get(0).get("body").asText(), "newest first");
        assertEquals("A post 1", feed.get(1).get("body").asText());
    }

    @Test
    void feedRequiresAuthenticationAndPostingRequiresAuthentication() throws Exception {
        String ownerToken = register("feed-auth-owner");
        long churchId = createChurch(ownerToken, "Feed Auth Fixture Church");

        mockMvc.perform(get("/api/users/me/feed")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/churches/" + churchId + "/posts")
                .contentType("application/json").content("{\"body\":\"anon\"}"))
            .andExpect(status().isUnauthorized());
    }
}
