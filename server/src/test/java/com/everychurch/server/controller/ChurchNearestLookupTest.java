package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;

import com.everychurch.server.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fixture set of churches at known coordinates (Chicago, NYC, LA) exercising
 * GET /api/churches/nearest (Prompt 14, PostGIS KNN per Prompt 3). No
 * GOOGLE_GEOCODING_API_KEY is configured in tests, so these rely on the
 * "client-supplied coordinates win" path in ChurchService.applyDto rather than a live
 * geocoding call.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChurchNearestLookupTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // static: JUnit creates a fresh test instance per @Test method, and this suite
    // doesn't roll back the H2 database between tests, so an instance field would
    // restart at 0 and collide on the unique email constraint across test methods.
    private static final AtomicInteger userCounter = new AtomicInteger();

    private String authToken() throws Exception {
        RegisterRequest request = new RegisterRequest(
            "nearest-fixture-" + userCounter.incrementAndGet() + "@example.com", "password123", "Test User");
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private long createChurchAt(String name, double lat, double lng) throws Exception {
        String body = mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + authToken())
                .contentType("application/json")
                .content("{\"name\":\"" + name + "\",\"latitude\":" + lat + ",\"longitude\":" + lng + "}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    // Each test method below queries at a coordinate pair unique to that method (not
    // reused anywhere else in this file or RedirectControllerTest) — the H2 database
    // is shared and never reset across tests, so reusing a query point would let one
    // method's fixture tie with another's and make sort order/identity nondeterministic.

    @Test
    void nearestReturnsClosestFirst() throws Exception {
        long fixtureA = createChurchAt("Nearest Order Fixture A", 50.10, 10.10);
        long fixtureB = createChurchAt("Nearest Order Fixture B", 48.50, 9.00);
        long fixtureC = createChurchAt("Nearest Order Fixture C", 44.00, 3.00);

        // A generous limit: the H2 database is shared across this whole test run, so
        // other tests' geocoded fixtures may also come back — assertRelativeOrder
        // below only checks the relative order of these three among whatever else
        // is present, rather than assuming they occupy positions 0-2.
        String body = mockMvc.perform(get("/api/churches/nearest")
                .param("lat", "50.10")
                .param("lng", "10.10")
                .param("limit", "1000"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        var results = objectMapper.readTree(body);
        assertRelativeOrder(results, fixtureA, fixtureB, fixtureC);
    }

    @Test
    void limitCapsTheNumberOfResults() throws Exception {
        createChurchAt("Limit Fixture A", 52.00, 12.00);
        createChurchAt("Limit Fixture B", 53.00, 13.00);
        createChurchAt("Limit Fixture C", 54.00, 14.00);

        mockMvc.perform(get("/api/churches/nearest")
                .param("lat", "52.00")
                .param("lng", "12.00")
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void churchesWithoutCoordinatesAreExcluded() throws Exception {
        mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + authToken())
                .contentType("application/json")
                .content("{\"name\":\"No Coordinates Fixture Church\"}"))
            .andExpect(status().isCreated());

        long withCoords = createChurchAt("Has Coordinates Fixture Church", 56.00, 16.00);

        String body = mockMvc.perform(get("/api/churches/nearest")
                .param("lat", "56.00")
                .param("lng", "16.00")
                .param("limit", "50"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        var results = objectMapper.readTree(body);
        for (var node : results) {
            org.junit.jupiter.api.Assertions.assertNotEquals("No Coordinates Fixture Church", node.get("name").asText());
        }
        org.junit.jupiter.api.Assertions.assertEquals(withCoords, results.get(0).get("id").asLong());
    }

    @Test
    void radiusExcludesFartherCandidates() throws Exception {
        // ~1km and ~111km east of the query point at the equator, respectively
        // (1 degree of longitude ≈ 111km there) — a 5km radius should keep the first
        // and exclude the second.
        long near = createChurchAt("Radius Fixture Near", 0.00, 20.01);
        long far = createChurchAt("Radius Fixture Far", 0.00, 21.00);

        String body = mockMvc.perform(get("/api/churches/nearest")
                .param("lat", "0.00")
                .param("lng", "20.00")
                .param("radiusMeters", "5000")
                .param("limit", "50"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        var results = objectMapper.readTree(body);
        boolean sawNear = false;
        for (var node : results) {
            long id = node.get("id").asLong();
            org.junit.jupiter.api.Assertions.assertNotEquals(far, id, "Far fixture should be excluded by radiusMeters");
            if (id == near) sawNear = true;
        }
        org.junit.jupiter.api.Assertions.assertTrue(sawNear, "Near fixture should be included within radiusMeters");
    }

    /** Asserts each id appears, in this relative order, without assuming no other rows are interleaved. */
    private void assertRelativeOrder(com.fasterxml.jackson.databind.JsonNode results, long... expectedIdsInRelativeOrder) {
        int previousPosition = -1;
        for (long id : expectedIdsInRelativeOrder) {
            int position = -1;
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).get("id").asLong() == id) {
                    position = i;
                    break;
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(position >= 0, "Expected church id " + id + " to appear in results");
            org.junit.jupiter.api.Assertions.assertTrue(position > previousPosition,
                "Expected church id " + id + " to rank after the previously checked church");
            previousPosition = position;
        }
    }
}
