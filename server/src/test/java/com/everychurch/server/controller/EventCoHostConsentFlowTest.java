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

/**
 * Co-host consent (found in review, 2026-09-23): an organizer with MANAGE_EVENTS on
 * just one church used to be able to list ANY other church as a co-host with no say
 * from it at all. A church the organizer can't manage now starts PENDING and only
 * becomes a public co-host once ITS OWN MANAGE_EVENTS staff accepts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventCoHostConsentFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final AtomicInteger counter = new AtomicInteger();

    private String register(String prefix) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new RegisterRequest(
                    prefix + "-" + counter.incrementAndGet() + "@example.com", "password123", "Test User"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private long createChurch(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("name", name))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Claims the church so ownerToken's user becomes its owner (full permissions,
     *  including MANAGE_EVENTS) — same flow ClaimAndStaffFlowTest/PostFeedFlowTest use. */
    private void claim(String ownerToken, long churchId) throws Exception {
        String claimBody = mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{}"))
            .andReturn().getResponse().getContentAsString();
        long claimId = objectMapper.readTree(claimBody).get("id").asLong();
        mockMvc.perform(post("/api/admin/claims/" + claimId + "/approve").header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isOk());
    }

    private String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"admin@everychurch.local\",\"password\":\"test-admin-password-123\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private long createEvent(String token, String title, long... churchIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long id : churchIds) ids.append(ids.isEmpty() ? "" : ",").append(id);
        String body = mockMvc.perform(post("/api/events")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"title\":\"" + title + "\",\"startsAt\":\"2027-01-01T18:00:00Z\",\"churchIds\":[" + ids + "]}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void aChurchTheOrganizerCantManageStartsPendingNotConfirmed() throws Exception {
        String organizerToken = register("organizer");
        long ownChurch = createChurch(organizerToken, "Organizer's Own Church");
        claim(organizerToken, ownChurch);

        String otherOwnerToken = register("other-owner");
        long otherChurch = createChurch(otherOwnerToken, "Uninvolved Church");
        claim(otherOwnerToken, otherChurch);

        long eventId = createEvent(organizerToken, "Joint Worship Night", ownChurch, otherChurch);

        String body = mockMvc.perform(get("/api/events/" + eventId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode dto = objectMapper.readTree(body);

        // public "hosted by" list only shows the church that's actually confirmed
        assertEquals(1, dto.get("churches").size());
        assertEquals("Organizer's Own Church", dto.get("churches").get(0).get("name").asText());
        // but the uninvited church shows up as a pending ask, visible to managers
        assertEquals(1, dto.get("pendingChurches").size());
        assertEquals("Uninvolved Church", dto.get("pendingChurches").get(0).get("name").asText());
        // churchIds (edit-form preselection) still includes it, so re-saving doesn't drop the invite
        assertEquals(2, dto.get("churchIds").size());
    }

    @Test
    void onlyTheInvitedChurchsOwnStaffCanAcceptOrDecline() throws Exception {
        String organizerToken = register("organizer2");
        long ownChurch = createChurch(organizerToken, "Organizer Church Two");
        claim(organizerToken, ownChurch);

        String invitedOwnerToken = register("invited-owner");
        long invitedChurch = createChurch(invitedOwnerToken, "Invited Church");
        claim(invitedOwnerToken, invitedChurch);

        long eventId = createEvent(organizerToken, "Co-Host Test Event", ownChurch, invitedChurch);

        // the organizer cannot accept on the invited church's behalf
        mockMvc.perform(post("/api/events/" + eventId + "/co-hosts/" + invitedChurch + "/accept")
                .header("Authorization", "Bearer " + organizerToken))
            .andExpect(status().isForbidden());

        // a random stranger can't either
        String strangerToken = register("stranger");
        mockMvc.perform(post("/api/events/" + eventId + "/co-hosts/" + invitedChurch + "/accept")
                .header("Authorization", "Bearer " + strangerToken))
            .andExpect(status().isForbidden());

        // the invited church's own owner can
        String acceptBody = mockMvc.perform(post("/api/events/" + eventId + "/co-hosts/" + invitedChurch + "/accept")
                .header("Authorization", "Bearer " + invitedOwnerToken))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode dto = objectMapper.readTree(acceptBody);
        assertEquals(2, dto.get("churches").size(), "now publicly confirmed on both sides");
        assertEquals(0, dto.get("pendingChurches").size());
    }

    @Test
    void decliningRemovesTheChurchFromTheEventEntirelyAndCannotBeRespondedToTwice() throws Exception {
        String organizerToken = register("organizer3");
        long ownChurch = createChurch(organizerToken, "Organizer Church Three");
        claim(organizerToken, ownChurch);

        String invitedOwnerToken = register("invited-owner2");
        long invitedChurch = createChurch(invitedOwnerToken, "Declining Church");
        claim(invitedOwnerToken, invitedChurch);

        long eventId = createEvent(organizerToken, "Declined Event", ownChurch, invitedChurch);

        mockMvc.perform(post("/api/events/" + eventId + "/co-hosts/" + invitedChurch + "/decline")
                .header("Authorization", "Bearer " + invitedOwnerToken))
            .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/events/" + eventId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode dto = objectMapper.readTree(body);
        assertEquals(1, dto.get("churches").size(), "declined church never appears as a public co-host");
        assertEquals(0, dto.get("pendingChurches").size());
        assertEquals(1, dto.get("churchIds").size(), "declined church is dropped from churchIds too");

        // responding again (after already declining) is rejected
        mockMvc.perform(post("/api/events/" + eventId + "/co-hosts/" + invitedChurch + "/accept")
                .header("Authorization", "Bearer " + invitedOwnerToken))
            .andExpect(status().isConflict());
    }

    @Test
    void pendingInvitationShowsUpInTheInvitedChurchsInvitationsListAndDisappearsAfterResponding() throws Exception {
        String organizerToken = register("organizer4");
        long ownChurch = createChurch(organizerToken, "Organizer Church Four");
        claim(organizerToken, ownChurch);

        String invitedOwnerToken = register("invited-owner3");
        long invitedChurch = createChurch(invitedOwnerToken, "Invitations List Church");
        claim(invitedOwnerToken, invitedChurch);

        long eventId = createEvent(organizerToken, "Invitations List Event", ownChurch, invitedChurch);

        String body = mockMvc.perform(get("/api/users/me/co-host-invitations")
                .header("Authorization", "Bearer " + invitedOwnerToken))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode invitations = objectMapper.readTree(body);
        assertEquals(1, invitations.size());
        assertEquals(eventId, invitations.get(0).get("eventId").asLong());
        assertEquals("Invitations List Church", invitations.get(0).get("churchName").asText());

        // the organizer's own invitations list is empty — they weren't invited to anything
        mockMvc.perform(get("/api/users/me/co-host-invitations").header("Authorization", "Bearer " + organizerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/events/" + eventId + "/co-hosts/" + invitedChurch + "/accept")
                .header("Authorization", "Bearer " + invitedOwnerToken))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/me/co-host-invitations").header("Authorization", "Bearer " + invitedOwnerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void platformAdminCanRespondOnBehalfOfAnyChurch() throws Exception {
        String organizerToken = register("organizer5");
        long ownChurch = createChurch(organizerToken, "Organizer Church Five");
        claim(organizerToken, ownChurch);

        String invitedOwnerToken = register("invited-owner4");
        long invitedChurch = createChurch(invitedOwnerToken, "Admin Response Church");
        claim(invitedOwnerToken, invitedChurch);

        long eventId = createEvent(organizerToken, "Admin Response Event", ownChurch, invitedChurch);

        mockMvc.perform(post("/api/events/" + eventId + "/co-hosts/" + invitedChurch + "/accept")
                .header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pendingChurches.length()").value(0));
    }
}
