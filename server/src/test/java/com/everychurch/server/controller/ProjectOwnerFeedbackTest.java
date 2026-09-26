package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.dto.LoginRequest;
import com.everychurch.server.dto.RegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * Covers the project-owner feedback recorded in docs/PROMPTS.md's "Prompt 16 findings":
 * required display names, admin-only editing of unclaimed churches, the duplicate-check
 * hint, event co-hosts by name, and the service-time summary field.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProjectOwnerFeedbackTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email, String displayName) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, "password123", displayName))))
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
        return createChurch(token, name, "{}");
    }

    private long createChurch(String token, String name, String extraJson) throws Exception {
        String body = mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(extraJson.equals("{}") ? "{\"name\":\"" + name + "\"}" : extraJson))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    // ── display names ───────────────────────────────────────────────────────

    @Test
    void registrationRequiresADisplayNameAndReturnsIt() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new RegisterRequest(uniqueEmail("nodisplay"), "password123", "   "))))
            .andExpect(status().isBadRequest());

        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new RegisterRequest(uniqueEmail("withname"), "password123", "Maria Gomez"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertEquals("Maria Gomez", objectMapper.readTree(body).get("displayName").asText());
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    // ── unclaimed churches: admin-only editing ──────────────────────────────

    @Test
    void editingAnUnclaimedChurchNowRequiresAPlatformAdmin() throws Exception {
        String owner = register(uniqueEmail("creator"), "Creator");
        long churchId = createChurch(owner, "Feedback Unclaimed Church");

        // the church's own creator is not automatically allowed to edit it while unclaimed
        mockMvc.perform(put("/api/churches/" + churchId)
                .header("Authorization", "Bearer " + owner)
                .contentType("application/json")
                .content("{\"name\":\"Renamed By Creator\"}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/churches/" + churchId)
                .header("Authorization", "Bearer " + adminToken())
                .contentType("application/json")
                .content("{\"name\":\"Renamed By Admin\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Renamed By Admin"));
    }

    @Test
    void deletingAnUnclaimedChurchAlsoRequiresAPlatformAdmin() throws Exception {
        // ChurchService.delete used to only gate deletion once a church was claimed
        // (via requireOwner) and fell through with NO check at all for an unclaimed
        // one — any authenticated user, not just its own creator, could delete any of
        // the ~42k unclaimed bulk-imported churches. Mirrors the update() test above.
        String creator = register(uniqueEmail("deleter-creator"), "Creator");
        String stranger = register(uniqueEmail("deleter-stranger"), "Stranger");
        long churchId = createChurch(creator, "Feedback Unclaimed Delete Church");

        mockMvc.perform(delete("/api/churches/" + churchId).header("Authorization", "Bearer " + creator))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/churches/" + churchId).header("Authorization", "Bearer " + stranger))
            .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/churches/" + churchId).header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/churches/" + churchId))
            .andExpect(status().isNotFound());
    }

    // ── duplicate-check ──────────────────────────────────────────────────────

    @Test
    void duplicateCheckFindsASimilarlyNamedChurchInTheSameCity() throws Exception {
        String token = register(uniqueEmail("dupe"), "Dupe Tester");
        createChurch(token, "Grace Community Fellowship",
            "{\"name\":\"Grace Community Fellowship\",\"city\":\"Springfield\",\"state\":\"IL\"}");

        mockMvc.perform(get("/api/churches/duplicate-check")
                .param("name", "Grace Community Church")
                .param("city", "Springfield"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Grace Community Fellowship"));

        // a same-name church in an unrelated city is not a hit
        mockMvc.perform(get("/api/churches/duplicate-check")
                .param("name", "Grace Community Fellowship")
                .param("city", "Nowhereville"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void pendingSubmissionsCarryPossibleDuplicatesButDecidedOnesDoNot() throws Exception {
        String owner = register(uniqueEmail("existing"), "Existing Owner");
        createChurch(owner, "Riverside Chapel", "{\"name\":\"Riverside Chapel\",\"city\":\"Austin\",\"state\":\"TX\"}");

        String admin = adminToken();
        String inviteBody = mockMvc.perform(post("/api/admin/volunteer-invites")
                .header("Authorization", "Bearer " + admin)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode invite = objectMapper.readTree(inviteBody);

        String volunteerBody = mockMvc.perform(post("/api/auth/register/volunteer")
                .contentType("application/json")
                .content("{\"code\":\"" + invite.get("code").asText() + "\",\"email\":\"" + uniqueEmail("volunteer")
                    + "\",\"password\":\"password123\",\"displayName\":\"A Volunteer\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String volunteerToken = objectMapper.readTree(volunteerBody).get("token").asText();

        String submissionBody = mockMvc.perform(post("/api/submissions")
                .header("Authorization", "Bearer " + volunteerToken)
                .contentType("application/json")
                .content("{\"proposedChanges\":{\"name\":\"Riverside Chapel\",\"city\":\"Austin\",\"state\":\"TX\"}}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long submissionId = objectMapper.readTree(submissionBody).get("id").asLong();

        mockMvc.perform(get("/api/admin/submissions").param("status", "PENDING")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + submissionId + ")].possibleDuplicates[0].name").value("Riverside Chapel"));

        mockMvc.perform(post("/api/admin/submissions/" + submissionId + "/reject")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.possibleDuplicates.length()").value(0));
    }

    // ── events: co-hosts by name ─────────────────────────────────────────────

    @Test
    void eventsListChurchesByIdAndName() throws Exception {
        String admin = adminToken();
        long churchId = createChurch(admin, "Event Host Church");

        String body = mockMvc.perform(post("/api/events")
                .header("Authorization", "Bearer " + admin)
                .contentType("application/json")
                .content("{\"title\":\"Fall Gathering\",\"startsAt\":\"2027-01-01T00:00:00Z\",\"churchIds\":[" + churchId + "]}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        JsonNode event = objectMapper.readTree(body);
        assertEquals("Event Host Church", event.get("churches").get(0).get("name").asText());
        assertEquals(String.valueOf(churchId), event.get("churches").get(0).get("id").asText());
    }

    // ── service-time summary ─────────────────────────────────────────────────

    @Test
    void listedChurchesCarryAServiceSummaryWithoutTheFullSchedule() throws Exception {
        String admin = adminToken();
        long churchId = createChurch(admin, "Summary Church",
            "{\"name\":\"Summary Church\",\"serviceTimes\":["
                + "{\"dayOfWeek\":\"Sunday\",\"time\":\"09:00\",\"type\":\"Sunday Service\"},"
                + "{\"dayOfWeek\":\"Sunday\",\"time\":\"11:00\",\"type\":\"Sunday Service\"},"
                + "{\"dayOfWeek\":\"Wednesday\",\"time\":\"19:00\",\"type\":\"Bible Study\"}]}");

        mockMvc.perform(get("/api/churches").param("query", "Summary Church"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].serviceSummary").value("Sundays 9:00 AM & 11:00 AM +1 more"))
            // the list endpoint deliberately never loads the full schedule
            .andExpect(jsonPath("$.content[0].serviceTimes.length()").value(0));

        mockMvc.perform(get("/api/churches/" + churchId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.serviceSummary").value("Sundays 9:00 AM & 11:00 AM +1 more"))
            .andExpect(jsonPath("$.serviceTimes.length()").value(3));
    }
}
