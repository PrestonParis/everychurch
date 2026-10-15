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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/churches (Prompt 2): pagination, pg_trgm fuzzy search, and filter
 * combining. Fixture names/cities/denominations below are unique to this file so
 * assertions can't be thrown off by other tests' fixtures sharing the same database
 * (see the comment in ChurchNearestLookupTest about the H2-era shared-DB pattern —
 * still true now that it's a shared Testcontainers Postgres instead).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChurchSearchTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final AtomicInteger userCounter = new AtomicInteger();

    private String authToken() throws Exception {
        RegisterRequest request = new RegisterRequest(
            "search-fixture-" + userCounter.incrementAndGet() + "@example.com", "password123", "Test User");
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private void createChurch(String name, String denomination, String state, String city) throws Exception {
        mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + authToken())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "name", name, "denomination", denomination, "state", state, "city", city))))
            .andExpect(status().isCreated());
    }

    @Test
    void resultsArePaginated() throws Exception {
        for (int i = 0; i < 3; i++) {
            createChurch("Pagination Fixture Church " + i, "Baptist", "PZ", "Pageburg");
        }

        String body = mockMvc.perform(get("/api/churches")
                .param("state", "PZ")
                .param("city", "Pageburg")
                .param("size", "2")
                .param("page", "0"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode page = objectMapper.readTree(body);
        assertEquals(2, page.get("content").size());
        assertEquals(3, page.get("totalElements").asInt());
        assertEquals(2, page.get("totalPages").asInt());
        assertEquals(0, page.get("number").asInt());
    }

    @Test
    void fuzzyNameSearchMatchesPartialInput() throws Exception {
        createChurch("Trinity Fellowship Amarillo", "Non-denominational", "QX", "Amarillo Fixture");

        mockMvc.perform(get("/api/churches").param("query", "Trinty Felowship"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.name == 'Trinity Fellowship Amarillo')]").exists());
    }

    @Test
    void filtersCombineWithSearch() throws Exception {
        createChurch("Combined Filter Grace Church", "Lutheran", "RY", "Combineville");
        createChurch("Combined Filter Grace Chapel", "Baptist", "RY", "Combineville");

        String body = mockMvc.perform(get("/api/churches")
                .param("query", "Combined Filter Grace")
                .param("denomination", "Lutheran")
                .param("state", "RY")
                .param("city", "Combineville"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(body).get("content");
        assertEquals(1, content.size());
        assertEquals("Combined Filter Grace Church", content.get(0).get("name").asText());
    }

    @Test
    void cityStateAndDenominationFiltersAreCaseInsensitive() throws Exception {
        // Real bulk-imported data is stored upper-cased ("CHICAGO", "IL") — a filter
        // that only matched on exact case returned nothing for naturally-cased input
        // (see V8__normalize_denominations_and_case_insensitive_filters.sql).
        createChurch("Case Insensitive Fixture Church", "Presbyterian", "CZ", "Caseville");

        mockMvc.perform(get("/api/churches")
                .param("city", "caseville")
                .param("state", "cz")
                .param("denomination", "presbyterian"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.name == 'Case Insensitive Fixture Church')]").exists());
    }

    @Test
    void denominationFilterMatchesRawAbbreviatedData() throws Exception {
        // Bulk-imported/scraped data isn't guaranteed to already be normalized to the
        // curated DENOMINATIONS labels (see ChurchRepository.search's keyword-matching
        // comment) — an exact match on "Baptist" must still find a church stored as "SBC".
        createChurch("Abbreviation Fixture Church", "SBC", "AZ", "Abbrevton");

        mockMvc.perform(get("/api/churches")
                .param("denomination", "Baptist")
                .param("state", "AZ")
                .param("city", "Abbrevton"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.name == 'Abbreviation Fixture Church')]").exists());
    }

    @Test
    void churchOfChristFilterMatchesItsLabelButNotTheUnitedChurchOfChrist() throws Exception {
        // The filter used to match only a raw "COC" code, never the stored label, so it
        // returned nothing in production and these churches showed under "Other".
        createChurch("Cedar Creek Fixture Church of Christ", "Church of Christ", "QC", "Cocton");
        createChurch("Pilgrim Fixture United Church of Christ", "United Church of Christ", "QC", "Cocton");

        mockMvc.perform(get("/api/churches").param("denomination", "Church of Christ").param("state", "QC"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].name").value("Cedar Creek Fixture Church of Christ"));

        mockMvc.perform(get("/api/churches").param("denomination", "Other").param("state", "QC"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].name").value("Pilgrim Fixture United Church of Christ"));
    }

    @Test
    void citySuggestionsMatchPrefixCaseInsensitively() throws Exception {
        createChurch("Suggestion Fixture Church", "Methodist", "SU", "Suggestville");

        mockMvc.perform(get("/api/churches/cities").param("prefix", "SUGG"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@ == 'Suggestville')]").exists());
    }

    @Test
    void listResultsOmitServiceTimesButSingleFetchIncludesThem() throws Exception {
        String token = authToken();
        String body = mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("""
                    {"name":"Service Times Fixture Church","state":"SV","city":"Servington",
                     "serviceTimes":[{"dayOfWeek":"Sunday","time":"09:00","type":"Service"}]}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).get("id").asLong();

        String listBody = mockMvc.perform(get("/api/churches").param("query", "Service Times Fixture Church"))
            .andReturn().getResponse().getContentAsString();
        JsonNode listResult = objectMapper.readTree(listBody).get("content").get(0);
        assertTrue(listResult.get("serviceTimes").isEmpty());

        mockMvc.perform(get("/api/churches/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.serviceTimes.length()").value(1))
            .andExpect(jsonPath("$.serviceTimes[0].dayOfWeek").value("Sunday"));
    }
}
