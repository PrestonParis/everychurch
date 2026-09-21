package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scraper's ingestion endpoint (docs/SCRAPER.md), authenticated via the
 * X-Scraper-Api-Key shared secret rather than a user JWT — see ScraperApiKeyFilter
 * and SecurityConfig's hasRole("SCRAPER") rule on /api/scraper/**.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScraperSubmissionFlowTest extends AbstractIntegrationTest {

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

    @Test
    void validKeySubmitsChurchesIntoTheReviewQueueAsScraper() throws Exception {
        String body = mockMvc.perform(post("/api/scraper/church-submissions")
                .header("X-Scraper-Api-Key", VALID_KEY)
                .contentType("application/json")
                .content("{\"submissions\":[{\"name\":\"Scraped Church One\"},{\"name\":\"Scraped Church Two\"}]}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.accepted").value(2))
            .andReturn().getResponse().getContentAsString();

        assertAcceptedFieldPresent(body);

        mockMvc.perform(get("/api/admin/submissions?status=PENDING")
                .header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.submittedBy == 'scraper')]").isNotEmpty());
    }

    private void assertAcceptedFieldPresent(String body) throws Exception {
        objectMapper.readTree(body).get("accepted").asInt();
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/scraper/church-submissions")
                .contentType("application/json")
                .content("{\"submissions\":[]}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/scraper/church-submissions")
                .header("X-Scraper-Api-Key", "not-the-real-key")
                .contentType("application/json")
                .content("{\"submissions\":[]}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void aRegularUserJwtDoesNotSubstituteForTheScraperKey() throws Exception {
        mockMvc.perform(post("/api/scraper/church-submissions")
                .header("Authorization", "Bearer " + adminToken())
                .contentType("application/json")
                .content("{\"submissions\":[]}"))
            .andExpect(status().isForbidden());
    }
}
