package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.dto.RegisterRequest;
import com.everychurch.server.service.DirectMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DM blocking and the generic user/church report — both flagged as outstanding
 *  moderation work described in ARCHITECTURE.md's
 *  Chat "Known limitations". send()'s block check is exercised via a direct service
 *  call rather than a live STOMP connection — DirectChatFlowTest already establishes
 *  that a rejected send is silently dropped at the WebSocket layer for other reasons
 *  (blank body, non-existent recipient); this only needs to prove the block check
 *  itself fires, which doesn't need a real socket. */
@SpringBootTest
@AutoConfigureMockMvc
class BlockAndReportFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DirectMessageService directMessageService;
    @Autowired com.everychurch.server.repository.UserRepository userRepository;

    private static final AtomicInteger counter = new AtomicInteger();

    private record Registered(String token, long id) {}

    private Registered register(String prefix) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new RegisterRequest(
                    prefix + "-" + counter.incrementAndGet() + "@example.com", "password123", "Test User"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return new Registered(json.get("token").asText(), json.get("id").asLong());
    }

    private String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"admin@everychurch.local\",\"password\":\"test-admin-password-123\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    // ── blocking ─────────────────────────────────────────────────────────────

    @Test
    void blockUnblockLifecycleAndDuplicateBlockIsAConflict() throws Exception {
        Registered a = register("blocker");
        Registered b = register("blockee");
        String auth = "Bearer " + a.token();

        mockMvc.perform(get("/api/users/" + b.id() + "/block").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.blocked").value(false));

        mockMvc.perform(post("/api/users/" + b.id() + "/block").header("Authorization", auth))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/users/" + b.id() + "/block").header("Authorization", auth))
            .andExpect(status().isConflict());

        mockMvc.perform(get("/api/users/" + b.id() + "/block").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.blocked").value(true));

        mockMvc.perform(delete("/api/users/" + b.id() + "/block").header("Authorization", auth))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/users/" + b.id() + "/block").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void cannotBlockYourself() throws Exception {
        Registered a = register("self-blocker");
        mockMvc.perform(post("/api/users/" + a.id() + "/block").header("Authorization", "Bearer " + a.token()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void blockingStopsDirectMessagesInEitherDirection() throws Exception {
        Registered a = register("blocking-sender");
        Registered b = register("blocking-recipient");

        // works before any block
        directMessageService.send(userOf(a), b.id(), "hello before block");

        mockMvc.perform(post("/api/users/" + b.id() + "/block").header("Authorization", "Bearer " + a.token()))
            .andExpect(status().isCreated());

        // a can no longer message b...
        assertThrows(IllegalStateException.class, () -> directMessageService.send(userOf(a), b.id(), "hello after block"));
        // ...and b can't message a either, even though b isn't the one who blocked
        assertThrows(IllegalStateException.class, () -> directMessageService.send(userOf(b), a.id(), "let me back in"));
    }

    private com.everychurch.server.model.User userOf(Registered r) {
        return userRepository.findById(r.id()).orElseThrow();
    }

    // ── reports ──────────────────────────────────────────────────────────────

    @Test
    void anyAuthenticatedUserCanFileAReportAndOnlyAnAdminCanReviewIt() throws Exception {
        Registered reporter = register("reporter");
        Registered target = register("reported-user");

        String createBody = mockMvc.perform(post("/api/reports")
                .header("Authorization", "Bearer " + reporter.token())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("targetType", "USER", "targetId", target.id(), "reason", "Harassing me in DMs"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.targetType").value("USER"))
            .andReturn().getResponse().getContentAsString();
        long reportId = objectMapper.readTree(createBody).get("id").asLong();

        // a non-admin can't review reports
        mockMvc.perform(get("/api/admin/reports").header("Authorization", "Bearer " + reporter.token()))
            .andExpect(status().isForbidden());

        String admin = "Bearer " + adminToken();
        mockMvc.perform(get("/api/admin/reports?status=OPEN").header("Authorization", admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + reportId + ")]").exists())
            .andExpect(jsonPath("$[?(@.id == " + reportId + ")].targetLabel").exists());

        mockMvc.perform(patch("/api/admin/reports/" + reportId)
                .header("Authorization", admin)
                .contentType("application/json")
                .content("{\"status\":\"DISMISSED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISMISSED"))
            .andExpect(jsonPath("$.resolvedByDisplayName").exists());

        // no longer shows up under the OPEN filter
        mockMvc.perform(get("/api/admin/reports?status=OPEN").header("Authorization", admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + reportId + ")]").doesNotExist());
    }

    @Test
    void reportingRequiresAReasonAndAValidTarget() throws Exception {
        Registered reporter = register("reporter2");
        String auth = "Bearer " + reporter.token();

        mockMvc.perform(post("/api/reports")
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{\"targetType\":\"USER\",\"targetId\":" + reporter.id() + ",\"reason\":\"   \"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/reports")
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{\"targetType\":\"USER\",\"targetId\":999999999,\"reason\":\"doesn't exist\"}"))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/reports")
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{\"targetType\":\"NOT_A_TYPE\",\"targetId\":" + reporter.id() + ",\"reason\":\"bad type\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reportingRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/reports")
                .contentType("application/json")
                .content("{\"targetType\":\"USER\",\"targetId\":1,\"reason\":\"x\"}"))
            .andExpect(status().isUnauthorized());
    }
}
