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
 * GET /api/config hands the client the one value it's allowed to see: the Google
 * Maps Embed key (safe to expose — restricted by HTTP referrer in Google Cloud
 * Console, not by secrecy). See PublicConfigControllerDefaultTest for the
 * unconfigured case, and ChurchMapComponent (client) for the graceful degradation
 * when the key is absent.
 */
@SpringBootTest(properties = "google.maps.embed-api-key=test-embed-key-123")
@AutoConfigureMockMvc
class PublicConfigControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void anonymousRequestGetsTheConfiguredKeyBackWithNoAuth() throws Exception {
        mockMvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.googleMapsEmbedKey").value("test-embed-key-123"));
    }
}
