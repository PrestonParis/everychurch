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
 * End to end for the Prompt 10-12 church content types (ChurchImage, Event, Sermon):
 * reads are public, writes require the matching ChurchPermission (EDIT_PROFILE,
 * MANAGE_EVENTS, MANAGE_SERMONS) via ChurchStaff, per docs/DATA_MODEL.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChurchContentFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String register(String email, String password) throws Exception {
        RegisterRequest request = new RegisterRequest(email, password);
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

    /** Claims and immediately approves {@code churchId} on {@code ownerToken}'s behalf, granting them full ChurchStaff ownership. */
    private void makeOwner(String ownerToken, long churchId, String adminToken) throws Exception {
        String claimBody = mockMvc.perform(post("/api/churches/" + churchId + "/claims")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long claimId = objectMapper.readTree(claimBody).get("id").asLong();

        mockMvc.perform(post("/api/admin/claims/" + claimId + "/approve")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    @Test
    void churchImageLifecycleIsGatedByEditProfile() throws Exception {
        String ownerToken = register("imageowner@example.com", "password123");
        long churchId = createChurch(ownerToken, "Image Test Church");
        makeOwner(ownerToken, churchId, adminToken());

        // reads are public
        mockMvc.perform(get("/api/churches/" + churchId + "/images"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // a stranger can't add one
        String strangerToken = register("imagestranger@example.com", "password123");
        mockMvc.perform(post("/api/churches/" + churchId + "/images")
                .header("Authorization", "Bearer " + strangerToken)
                .contentType("application/json")
                .content("{\"url\":\"https://example.com/hijack.jpg\",\"sortOrder\":0}"))
            .andExpect(status().isForbidden());

        // the owner can
        String addBody = mockMvc.perform(post("/api/churches/" + churchId + "/images")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"url\":\"https://example.com/sanctuary.jpg\",\"sortOrder\":0}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.url").value("https://example.com/sanctuary.jpg"))
            .andReturn().getResponse().getContentAsString();
        long imageId = objectMapper.readTree(addBody).get("id").asLong();

        mockMvc.perform(get("/api/churches/" + churchId + "/images"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        // the owner can update it
        mockMvc.perform(put("/api/churches/" + churchId + "/images/" + imageId)
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"url\":\"https://example.com/sanctuary2.jpg\",\"sortOrder\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortOrder").value(1));

        // and remove it
        mockMvc.perform(delete("/api/churches/" + churchId + "/images/" + imageId)
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/churches/" + churchId + "/images"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void sermonLifecycleIsGatedByManageSermonsNotJustEditProfile() throws Exception {
        String ownerToken = register("sermonowner@example.com", "password123");
        long churchId = createChurch(ownerToken, "Sermon Test Church");
        makeOwner(ownerToken, churchId, adminToken());

        // a staff member with only EDIT_PROFILE (not MANAGE_SERMONS) can't manage sermons
        String staffToken = register("sermonstaff@example.com", "password123");
        mockMvc.perform(post("/api/churches/" + churchId + "/staff")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"email\":\"sermonstaff@example.com\",\"permissions\":[\"EDIT_PROFILE\"]}"))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/churches/" + churchId + "/sermons")
                .header("Authorization", "Bearer " + staffToken)
                .contentType("application/json")
                .content("{\"title\":\"Sunday Sermon\",\"date\":\"2026-01-04\",\"mediaUrl\":\"https://example.com/sermon.mp3\"}"))
            .andExpect(status().isForbidden());

        // the owner can add one
        String addBody = mockMvc.perform(post("/api/churches/" + churchId + "/sermons")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("{\"title\":\"Sunday Sermon\",\"date\":\"2026-01-04\",\"mediaUrl\":\"https://example.com/sermon.mp3\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("Sunday Sermon"))
            .andReturn().getResponse().getContentAsString();
        long sermonId = objectMapper.readTree(addBody).get("id").asLong();

        // reads are public
        mockMvc.perform(get("/api/churches/" + churchId + "/sermons"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(delete("/api/churches/" + churchId + "/sermons/" + sermonId)
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isNoContent());
    }

    @Test
    void eventRequiresManageEventsOnAtLeastOneParticipatingChurch() throws Exception {
        String admin = adminToken();

        String ownerAToken = register("eventownera@example.com", "password123");
        long churchA = createChurch(ownerAToken, "Event Church A");
        makeOwner(ownerAToken, churchA, admin);

        String ownerBToken = register("eventownerb@example.com", "password123");
        long churchB = createChurch(ownerBToken, "Event Church B");
        makeOwner(ownerBToken, churchB, admin);

        // a stranger to both churches can't create an event for either
        String strangerToken = register("eventstranger@example.com", "password123");
        mockMvc.perform(post("/api/events")
                .header("Authorization", "Bearer " + strangerToken)
                .contentType("application/json")
                .content("{\"title\":\"Hijacked\",\"startsAt\":\"2026-06-01T10:00:00Z\",\"churchIds\":[" + churchA + "]}"))
            .andExpect(status().isForbidden());

        // owner A can create a co-hosted event across both churches, needing MANAGE_EVENTS on only one
        String createBody = mockMvc.perform(post("/api/events")
                .header("Authorization", "Bearer " + ownerAToken)
                .contentType("application/json")
                .content("{\"title\":\"Joint Revival\",\"startsAt\":\"2026-06-01T10:00:00Z\",\"churchIds\":[" + churchA + "," + churchB + "]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("Joint Revival"))
            .andReturn().getResponse().getContentAsString();
        long eventId = objectMapper.readTree(createBody).get("id").asLong();

        // reads are public
        mockMvc.perform(get("/api/events/" + eventId))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/events").param("churchId", String.valueOf(churchB)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        // owner B (staff on the current church set via churchB) can update it, but not to drop down
        // to a church set they have no permission on at all
        mockMvc.perform(put("/api/events/" + eventId)
                .header("Authorization", "Bearer " + ownerBToken)
                .contentType("application/json")
                .content("{\"title\":\"Joint Revival\",\"startsAt\":\"2026-06-01T10:00:00Z\",\"churchIds\":[" + churchA + "]}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/events/" + eventId)
                .header("Authorization", "Bearer " + ownerAToken))
            .andExpect(status().isNoContent());
    }
}
