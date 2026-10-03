package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.model.Church;
import com.everychurch.server.repository.ChurchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one-time Kaggle bulk-import path (Prompt 7): a SEPARATE key from the live
 * scraper's, writing Church rows directly (unclaimed) rather than through the
 * ChurchSubmission review queue.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BulkImportFlowTest extends AbstractIntegrationTest {

    private static final String BULK_KEY = "test-only-bulk-import-key-not-for-real-use";
    private static final String SCRAPER_KEY = "test-only-scraper-key-not-for-real-use";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ChurchRepository churchRepository;

    @Test
    void validKeyCreatesUnclaimedChurchesDirectly() throws Exception {
        String body = "{\"churches\":[{\"name\":\"Bulk Seeded Church One\",\"zipCode\":\"02101\",\"claimed\":true},"
            + "{\"name\":\"Bulk Seeded Church Two\"}]}";

        mockMvc.perform(post("/api/scraper/bulk-import")
                .header("X-Bulk-Import-Api-Key", BULK_KEY)
                .contentType("application/json")
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.created").value(2));

        // live immediately (no review queue), and claimed is never settable from the payload
        mockMvc.perform(get("/api/churches").param("query", "Bulk Seeded Church One"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].name").value("Bulk Seeded Church One"))
            .andExpect(jsonPath("$.content[0].claimed").value(false));
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/scraper/bulk-import")
                .contentType("application/json")
                .content("{\"churches\":[]}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/scraper/bulk-import")
                .header("X-Bulk-Import-Api-Key", "not-the-real-key")
                .contentType("application/json")
                .content("{\"churches\":[]}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void theLiveScraperKeyDoesNotSubstituteForTheBulkImportKey() throws Exception {
        mockMvc.perform(post("/api/scraper/bulk-import")
                .header("X-Scraper-Api-Key", SCRAPER_KEY)
                .contentType("application/json")
                .content("{\"churches\":[]}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void theBulkImportKeyDoesNotSubstituteForTheScraperKey() throws Exception {
        mockMvc.perform(post("/api/scraper/church-submissions")
                .header("X-Bulk-Import-Api-Key", BULK_KEY)
                .contentType("application/json")
                .content("{\"submissions\":[]}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void importStatus_reachableWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/import-status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.churchCount").exists());
    }

    /** source/totalNew (see BulkImportStatusService) — self-contained: the two
     *  batches below always sum to exactly totalNew, so this test starts and ends
     *  at importRunning=false regardless of what else has run in this shared
     *  Spring context, and never leaves a dangling "running" state for another
     *  test to trip over. */
    @Test
    void bulkImportBatches_withProgressFields_reportMidRunThenComplete() throws Exception {
        mockMvc.perform(post("/api/scraper/bulk-import")
                .header("X-Bulk-Import-Api-Key", BULK_KEY)
                .contentType("application/json")
                .content("{\"churches\":[{\"name\":\"Progress Fixture Church One\"}],\"source\":\"kaggle\",\"totalNew\":2}"))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/import-status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.importRunning").value(true))
            .andExpect(jsonPath("$.totalRows").value(2))
            .andExpect(jsonPath("$.processedRows").value(1));

        mockMvc.perform(post("/api/scraper/bulk-import")
                .header("X-Bulk-Import-Api-Key", BULK_KEY)
                .contentType("application/json")
                .content("{\"churches\":[{\"name\":\"Progress Fixture Church Two\"}],\"source\":\"kaggle\",\"totalNew\":2}"))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/import-status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.importRunning").value(false))
            .andExpect(jsonPath("$.processedRows").value(2));
    }

    // --- /fill (osm_fill.py) ---------------------------------------------------------

    private Long saveChurch(String name, String website, boolean claimed) {
        Church c = new Church();
        c.setName(name);
        c.setWebsite(website);
        c.setClaimed(claimed);
        return churchRepository.save(c).getId();
    }

    private void fill(String body, int expectedUpdated) throws Exception {
        mockMvc.perform(post("/api/scraper/bulk-import/fill")
                .header("X-Bulk-Import-Api-Key", BULK_KEY)
                .contentType("application/json")
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(expectedUpdated));
    }

    @Test
    void fill_setsOnlyBlankFields_andAddsServiceTimesWhenNoneExist() throws Exception {
        Long id = saveChurch("Fill Fixture Church", "https://existing.example.org", false);

        fill("{\"source\":\"osm\",\"churches\":[{\"id\":" + id + ",\"name\":\"Renamed\","
            + "\"website\":\"https://osm.example.org\",\"phone\":\"401-555-0100\",\"denomination\":\"Baptist\","
            + "\"serviceTimes\":[{\"dayOfWeek\":\"Sunday\",\"time\":\"10:30\",\"type\":\"Worship\"}]}]}", 1);

        mockMvc.perform(get("/api/churches/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Fill Fixture Church"))
            .andExpect(jsonPath("$.website").value("https://existing.example.org"))
            .andExpect(jsonPath("$.phone").value("401-555-0100"))
            .andExpect(jsonPath("$.denomination").value("Baptist"))
            .andExpect(jsonPath("$.serviceSummary").value("Sundays 10:30 AM"))
            .andExpect(jsonPath("$.serviceTimes.length()").value(1));

        // nothing blank left that this dto could fill, and the schedule is never merged
        fill("{\"churches\":[{\"id\":" + id + ",\"phone\":\"401-555-0199\","
            + "\"serviceTimes\":[{\"dayOfWeek\":\"Saturday\",\"time\":\"17:00\",\"type\":\"Mass\"}]}]}", 0);
        mockMvc.perform(get("/api/churches/" + id))
            .andExpect(jsonPath("$.phone").value("401-555-0100"))
            .andExpect(jsonPath("$.serviceTimes.length()").value(1));
    }

    @Test
    void fill_neverTouchesAClaimedChurch_andSkipsUnknownIds() throws Exception {
        Long id = saveChurch("Claimed Fill Fixture Church", null, true);

        fill("{\"churches\":[{\"id\":" + id + ",\"website\":\"https://osm.example.org\"},"
            + "{\"id\":987654321,\"website\":\"https://nowhere.example.org\"}]}", 0);

        assertNull(churchRepository.findById(id).orElseThrow().getWebsite());
        assertEquals(false, churchRepository.existsById(987654321L));
    }

    @Test
    void fill_requiresTheBulkImportKey() throws Exception {
        mockMvc.perform(post("/api/scraper/bulk-import/fill")
                .header("X-Scraper-Api-Key", SCRAPER_KEY)
                .contentType("application/json")
                .content("{\"churches\":[]}"))
            .andExpect(status().isForbidden());
    }
}
