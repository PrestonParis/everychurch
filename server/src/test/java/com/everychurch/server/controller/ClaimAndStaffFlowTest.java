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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end: a member creates a church, requests a claim on it, an admin approves,
 * the requester becomes owner (and CHURCH_ADMIN), can manage the church and its
 * staff, and everyone else is locked out per docs/DATA_MODEL.md's Claim/ChurchStaff
 * rules.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimAndStaffFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String register(String email, String password) throws Exception {
        RegisterRequest request = new RegisterRequest(email, password, "Test User");
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

    @Test
    void fullClaimAndStaffLifecycle() throws Exception {
        String ownerToken = register("owner1@example.com", "password123");
        long churchId = createChurch(ownerToken, "Test Claim Church");

        String claimBody = mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"notes\":\"I am the pastor\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn().getResponse().getContentAsString();
        long claimId = objectMapper.readTree(claimBody).get("id").asLong();

        // a second pending claim on the same church by the same user is rejected
        mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isConflict());

        // a non-admin can't approve claims
        mockMvc.perform(post("/api/admin/claims/" + claimId + "/approve")
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isForbidden());

        String admin = adminToken();

        mockMvc.perform(post("/api/admin/claims/" + claimId + "/approve")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/churches/" + churchId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.claimed").value(true));

        // a stranger cannot edit the now-claimed church
        String strangerToken = register("stranger1@example.com", "password123");
        mockMvc.perform(put("/api/churches/" + churchId)
                .header("Authorization", "Bearer " + strangerToken)
                .contentType("application/json")
                .content("{\"name\":\"Hijacked Name\"}"))
            .andExpect(status().isForbidden());

        // the owner can edit
        mockMvc.perform(put("/api/churches/" + churchId)
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"name\":\"Updated By Owner\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Updated By Owner"));

        // owner adds a staff member with only EDIT_PROFILE
        String staffToken = register("staffmember1@example.com", "password123");
        String addStaffBody = mockMvc.perform(post("/api/churches/" + churchId + "/staff")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"email\":\"staffmember1@example.com\",\"permissions\":[\"EDIT_PROFILE\"]}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long staffId = objectMapper.readTree(addStaffBody).get("id").asLong();

        // that staff member can edit the profile...
        mockMvc.perform(put("/api/churches/" + churchId)
                .header("Authorization", "Bearer " + staffToken)
                .contentType("application/json")
                .content("{\"name\":\"Edited By Staff\"}"))
            .andExpect(status().isOk());

        // ...but can't delete the church (owner-only)...
        mockMvc.perform(delete("/api/churches/" + churchId)
                .header("Authorization", "Bearer " + staffToken))
            .andExpect(status().isForbidden());

        // ...and can't manage other staff (lacks MANAGE_STAFF)
        mockMvc.perform(get("/api/churches/" + churchId + "/staff")
                .header("Authorization", "Bearer " + staffToken))
            .andExpect(status().isForbidden());

        // the owner can list staff and sees both entries
        mockMvc.perform(get("/api/churches/" + churchId + "/staff")
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        // the owner removes the staff member
        mockMvc.perform(delete("/api/churches/" + churchId + "/staff/" + staffId)
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/churches/" + churchId + "/staff")
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void meStaffChurchesListsOnlyTheCallersOwnMemberships() throws Exception {
        String ownerToken = register("staffme-owner@example.com", "password123");
        long churchId = createChurch(ownerToken, "Staff Me Fixture Church");

        String claimBody = mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{}"))
            .andReturn().getResponse().getContentAsString();
        long claimId = objectMapper.readTree(claimBody).get("id").asLong();
        mockMvc.perform(post("/api/admin/claims/" + claimId + "/approve")
                .header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isOk());

        // owner sees their own ownership row
        mockMvc.perform(get("/api/users/me/staff-churches").header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].churchId").value(churchId))
            .andExpect(jsonPath("$[0].isOwner").value(true));

        // a stranger who staffs nothing sees an empty list, not the owner's row
        String strangerToken = register("staffme-stranger@example.com", "password123");
        mockMvc.perform(get("/api/users/me/staff-churches").header("Authorization", "Bearer " + strangerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // unauthenticated is rejected, not treated as "no memberships"
        mockMvc.perform(get("/api/users/me/staff-churches"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectedClaimLeavesChurchUnclaimed() throws Exception {
        String requesterToken = register("rejectedrequester@example.com", "password123");
        long churchId = createChurch(requesterToken, "Rejected Claim Church");

        String claimBody = mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + requesterToken)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long claimId = objectMapper.readTree(claimBody).get("id").asLong();

        mockMvc.perform(post("/api/admin/claims/" + claimId + "/reject")
                .header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(get("/api/churches/" + churchId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.claimed").value(false));

        // and the requester still can't edit it as if they owned it — anyone can,
        // since it's still unclaimed, but that's the pre-existing "no owner yet" rule
    }

    @Test
    void cannotClaimAnAlreadyClaimedChurch() throws Exception {
        String firstOwnerToken = register("firstowner@example.com", "password123");
        long churchId = createChurch(firstOwnerToken, "Twice Claimed Church");

        String claimBody = mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + firstOwnerToken)
                .contentType("application/json")
                .content("{}"))
            .andReturn().getResponse().getContentAsString();
        long claimId = objectMapper.readTree(claimBody).get("id").asLong();

        mockMvc.perform(post("/api/admin/claims/" + claimId + "/approve")
                .header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isOk());

        String secondClaimantToken = register("secondclaimant@example.com", "password123");
        mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + secondClaimantToken)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isConflict());
    }

    @Test
    void anonymousCannotRequestAClaim() throws Exception {
        String token = register("justamember@example.com", "password123");
        long churchId = createChurch(token, "Anon Test Church");

        mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
