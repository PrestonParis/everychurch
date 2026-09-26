package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;

import com.everychurch.server.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Volunteer field kit's public redirect (Prompt 15): GET /r/nearest needs no auth and
 * forwards a scan to the nearest church's profile on the Angular client.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedirectControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String authToken() throws Exception {
        RegisterRequest request = new RegisterRequest("redirect-fixture@example.com", "password123", "Test User");
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private long createChurchAt(double lat, double lng) throws Exception {
        String body = mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + authToken())
                .contentType("application/json")
                .content("{\"name\":\"Redirect Fixture Church\",\"latitude\":" + lat + ",\"longitude\":" + lng + "}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void redirectsAnonymouslyToTheNearestChurchProfile() throws Exception {
        // A coordinate pair not reused by any other test in this suite — the H2
        // database is shared across the whole run, so a collision would make this
        // "nearest" lookup nondeterministic (see ChurchNearestLookupTest).
        long churchId = createChurchAt(60.00, 20.00);

        mockMvc.perform(get("/r/nearest").param("lat", "60.00").param("lng", "20.00"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/churches/" + churchId)));
    }

    @Test
    void redirectsToClientBaseWhenNoChurchesAreGeocoded() throws Exception {
        mockMvc.perform(get("/r/nearest").param("lat", "-89").param("lng", "179"))
            .andExpect(status().isFound());
    }
}
