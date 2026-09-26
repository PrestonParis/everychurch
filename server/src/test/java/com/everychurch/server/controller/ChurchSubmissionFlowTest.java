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

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end for the Volunteer submission-and-verification queue (Prompt 13,
 * docs/DATA_MODEL.md `ChurchSubmission`): a volunteer proposes a new church and an
 * edit to an existing one, neither takes effect until a Platform Admin approves it,
 * rejection leaves the target untouched, and a non-Volunteer can't submit at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChurchSubmissionFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // static: JUnit creates a fresh test instance per @Test method, and this suite
    // doesn't roll back the H2 database between tests, so an instance field would
    // restart at 0 and collide on the unique email constraint across test methods.
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

    /** Admin generates an invite, a fresh recruit redeems it into a Role.VOLUNTEER account. */
    private String volunteerToken() throws Exception {
        String admin = adminToken();

        String inviteBody = mockMvc.perform(post("/api/admin/volunteer-invites")
                .header("Authorization", "Bearer " + admin)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String code = objectMapper.readTree(inviteBody).get("code").asText();

        String email = "volunteer" + counter.incrementAndGet() + "@example.com";
        VolunteerRegisterRequest volunteerRegister = new VolunteerRegisterRequest(code, email, "password123", "Test User");
        String body = mockMvc.perform(post("/api/auth/register/volunteer")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(volunteerRegister)))
            .andExpect(status().isCreated())
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
    void nonVolunteerCannotSubmit() throws Exception {
        String memberToken = register("plainmember" + counter.incrementAndGet() + "@example.com");

        mockMvc.perform(post("/api/submissions")
                .header("Authorization", "Bearer " + memberToken)
                .contentType("application/json")
                .content("{\"proposedChanges\":{\"name\":\"Should Be Rejected\"}}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotSubmit() throws Exception {
        mockMvc.perform(post("/api/submissions")
                .contentType("application/json")
                .content("{\"proposedChanges\":{\"name\":\"Anon Church\"}}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void newChurchSubmissionStaysPendingUntilApproved() throws Exception {
        String volunteer = volunteerToken();

        String submissionBody = mockMvc.perform(post("/api/submissions")
                .header("Authorization", "Bearer " + volunteer)
                .contentType("application/json")
                .content("{\"proposedChanges\":{\"name\":\"Volunteer Discovered Church\",\"city\":\"Chicago\"}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.targetChurchId").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        long submissionId = objectMapper.readTree(submissionBody).get("id").asLong();

        // not live yet
        mockMvc.perform(get("/api/churches"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name == 'Volunteer Discovered Church')]").isEmpty());

        String admin = adminToken();
        String approveBody = mockMvc.perform(post("/api/admin/submissions/" + submissionId + "/approve")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andReturn().getResponse().getContentAsString();
        long newChurchId = objectMapper.readTree(approveBody).get("targetChurchId").asLong();

        mockMvc.perform(get("/api/churches/" + newChurchId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Volunteer Discovered Church"))
            .andExpect(jsonPath("$.city").value("Chicago"));
    }

    @Test
    void editSubmissionAppliesOnApprovalOnly() throws Exception {
        String volunteer = volunteerToken();
        long churchId = createChurch(volunteer, "Pre-Edit Church Name");

        String submissionBody = mockMvc.perform(post("/api/churches/" + churchId + "/submissions")
                .header("Authorization", "Bearer " + volunteer)
                .contentType("application/json")
                .content("{\"proposedChanges\":{\"name\":\"Corrected Church Name\",\"phone\":\"555-1234\"}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.targetChurchId").value(churchId))
            .andReturn().getResponse().getContentAsString();
        long submissionId = objectMapper.readTree(submissionBody).get("id").asLong();

        // unchanged before approval
        mockMvc.perform(get("/api/churches/" + churchId))
            .andExpect(jsonPath("$.name").value("Pre-Edit Church Name"));

        String admin = adminToken();
        mockMvc.perform(post("/api/admin/submissions/" + submissionId + "/approve")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/churches/" + churchId))
            .andExpect(jsonPath("$.name").value("Corrected Church Name"))
            .andExpect(jsonPath("$.phone").value("555-1234"));
    }

    @Test
    void rejectionLeavesTargetChurchUntouched() throws Exception {
        String volunteer = volunteerToken();
        long churchId = createChurch(volunteer, "Should Stay The Same");

        String submissionBody = mockMvc.perform(post("/api/churches/" + churchId + "/submissions")
                .header("Authorization", "Bearer " + volunteer)
                .contentType("application/json")
                .content("{\"proposedChanges\":{\"name\":\"Rejected Name Change\"}}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long submissionId = objectMapper.readTree(submissionBody).get("id").asLong();

        String admin = adminToken();

        // a non-admin cannot reject
        mockMvc.perform(post("/api/admin/submissions/" + submissionId + "/reject")
                .header("Authorization", "Bearer " + volunteer))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/submissions/" + submissionId + "/reject")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(get("/api/churches/" + churchId))
            .andExpect(jsonPath("$.name").value("Should Stay The Same"));

        // and it can't be decided twice
        mockMvc.perform(post("/api/admin/submissions/" + submissionId + "/approve")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isConflict());
    }

    @Test
    void adminCanListSubmissionsFilteredByStatus() throws Exception {
        String volunteer = volunteerToken();
        mockMvc.perform(post("/api/submissions")
                .header("Authorization", "Bearer " + volunteer)
                .contentType("application/json")
                .content("{\"proposedChanges\":{\"name\":\"List Filter Fixture Church\"}}"))
            .andExpect(status().isCreated());

        String admin = adminToken();
        mockMvc.perform(get("/api/admin/submissions").param("status", "PENDING")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.proposedChanges.name == 'List Filter Fixture Church')]").isNotEmpty());
    }
}
