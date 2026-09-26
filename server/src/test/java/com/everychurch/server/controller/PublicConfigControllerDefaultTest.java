package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A separate top-level test class (not @Nested) so it gets its own Spring context
 * with no google.maps.embed-api-key override — the default, unconfigured case (no
 * GOOGLE_MAPS_EMBED_API_KEY set, as in a fresh dev checkout or CI).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicConfigControllerDefaultTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void unconfiguredKeyComesBackAsNullNotAnEmptyString() throws Exception {
        mockMvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.googleMapsEmbedKey").doesNotExist());
    }
}
