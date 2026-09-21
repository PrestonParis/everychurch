package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
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
 * Prompt 5 (docs/PROMPTS.md): scraper run tracking. Authenticated the same way as
 * /api/scraper/church-submissions — X-Scraper-Api-Key, not a user JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScraperRunFlowTest extends AbstractIntegrationTest {

    private static final String VALID_KEY = "test-only-scraper-key-not-for-real-use";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"admin@everychurch.local\",\"password\":\"test-admin-password-123\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String memberToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("{\"email\":\"scraperrun-member-" + java.util.UUID.randomUUID() + "@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private long startRun(String region) throws Exception {
        String body = mockMvc.perform(post("/api/scraper/runs")
                .header("X-Scraper-Api-Key", VALID_KEY)
                .contentType("application/json")
                .content(region == null ? "{}" : "{\"region\":\"" + region + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void startAndCompleteLifecycle() throws Exception {
        long runId = startRun("XX1");

        mockMvc.perform(patch("/api/scraper/runs/" + runId)
                .header("X-Scraper-Api-Key", VALID_KEY)
                .contentType("application/json")
                .content("{\"status\":\"COMPLETED\",\"discovered\":10,\"newCount\":3,\"accepted\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.discovered").value(10))
            .andExpect(jsonPath("$.newCount").value(3))
            .andExpect(jsonPath("$.accepted").value(3))
            .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void regionIsOptionalForALocalFullSweepRun() throws Exception {
        mockMvc.perform(post("/api/scraper/runs")
                .header("X-Scraper-Api-Key", VALID_KEY)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.region").doesNotExist());
    }

    @Test
    void completingAnUnknownRunIs404() throws Exception {
        mockMvc.perform(patch("/api/scraper/runs/99999999")
                .header("X-Scraper-Api-Key", VALID_KEY)
                .contentType("application/json")
                .content("{\"status\":\"FAILED\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void nextRegionPicksACandidateThatsNeverCompletedOverOneThatHas() throws Exception {
        long completedRunId = startRun("XX2");
        mockMvc.perform(patch("/api/scraper/runs/" + completedRunId)
                .header("X-Scraper-Api-Key", VALID_KEY)
                .contentType("application/json")
                .content("{\"status\":\"COMPLETED\",\"discovered\":1,\"newCount\":0,\"accepted\":0}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/scraper/runs/next-region")
                .header("X-Scraper-Api-Key", VALID_KEY)
                .param("candidates", "XX2", "XX3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.region").value("XX3"));
    }

    @Test
    void nextRegionPicksTheOldestCompletedWhenAllCandidatesHaveRun() throws Exception {
        long olderRun = startRun("XX4");
        mockMvc.perform(patch("/api/scraper/runs/" + olderRun)
                .header("X-Scraper-Api-Key", VALID_KEY)
                .contentType("application/json")
                .content("{\"status\":\"COMPLETED\",\"discovered\":1,\"newCount\":0,\"accepted\":0}"))
            .andExpect(status().isOk());

        Thread.sleep(10); // ensure a strictly later completedAt for the second run

        long newerRun = startRun("XX5");
        mockMvc.perform(patch("/api/scraper/runs/" + newerRun)
                .header("X-Scraper-Api-Key", VALID_KEY)
                .contentType("application/json")
                .content("{\"status\":\"COMPLETED\",\"discovered\":1,\"newCount\":0,\"accepted\":0}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/scraper/runs/next-region")
                .header("X-Scraper-Api-Key", VALID_KEY)
                .param("candidates", "XX4", "XX5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.region").value("XX4"));
    }

    @Test
    void adminCanListRunsMostRecentFirst() throws Exception {
        startRun("XX6");
        startRun("XX7");

        mockMvc.perform(get("/api/admin/scraper-runs")
                .header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].region").value("XX7"))
            .andExpect(jsonPath("$[1].region").value("XX6"));
    }

    @Test
    void aRegularUserCannotListAdminRuns() throws Exception {
        mockMvc.perform(get("/api/admin/scraper-runs")
                .header("Authorization", "Bearer " + memberToken()))
            .andExpect(status().isForbidden());
    }

    @Test
    void missingScraperKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/scraper/runs")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminTriggerWithAnExplicitRegionRecordsARunningRunAndReturnsAMessage() throws Exception {
        mockMvc.perform(post("/api/admin/scraper-runs/trigger")
                .header("Authorization", "Bearer " + adminToken())
                .contentType("application/json")
                .content("{\"region\":\"RI\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.run.region").value("RI"))
            .andExpect(jsonPath("$.run.status").value("RUNNING"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void adminTriggerWithNoRegionAutoPicksOne() throws Exception {
        mockMvc.perform(post("/api/admin/scraper-runs/trigger")
                .header("Authorization", "Bearer " + adminToken())
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.run.region").isNotEmpty())
            .andExpect(jsonPath("$.run.status").value("RUNNING"));
    }

    @Test
    void aRegularUserCannotTriggerARun() throws Exception {
        mockMvc.perform(post("/api/admin/scraper-runs/trigger")
                .header("Authorization", "Bearer " + memberToken())
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isForbidden());
    }
}
