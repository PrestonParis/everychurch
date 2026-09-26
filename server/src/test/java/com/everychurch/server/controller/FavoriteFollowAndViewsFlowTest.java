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

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Favorite, Follow, and "recently viewed" — all three personal, per-user
 *  relationships to a church, independent of each other and of Member/ChurchStaff. */
@SpringBootTest
@AutoConfigureMockMvc
class FavoriteFollowAndViewsFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final AtomicInteger userCounter = new AtomicInteger();

    private String authToken() throws Exception {
        RegisterRequest request = new RegisterRequest(
            "fav-fixture-" + userCounter.incrementAndGet() + "@example.com", "password123", "Test User");
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private long createChurch(String token, String name, String denomination) throws Exception {
        String body = mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("name", name, "denomination", denomination))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void favoriteJoinLeaveAndListLifecycle() throws Exception {
        String token = authToken();
        long churchId = createChurch(token, "Favorite Fixture Church", "Baptist");
        String auth = "Bearer " + token;

        mockMvc.perform(get("/api/churches/" + churchId + "/favorite").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.favorited").value(false));

        mockMvc.perform(post("/api/churches/" + churchId + "/favorite").header("Authorization", auth))
            .andExpect(status().isCreated());

        // favoriting the same church twice is a conflict, not a silent duplicate
        mockMvc.perform(post("/api/churches/" + churchId + "/favorite").header("Authorization", auth))
            .andExpect(status().isConflict());

        mockMvc.perform(get("/api/churches/" + churchId + "/favorite").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.favorited").value(true));

        mockMvc.perform(get("/api/users/me/favorites").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Favorite Fixture Church"));

        mockMvc.perform(delete("/api/churches/" + churchId + "/favorite").header("Authorization", auth))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me/favorites").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void favoritesAndFollowsAreIndependentOfEachOther() throws Exception {
        String token = authToken();
        long churchId = createChurch(token, "Independence Fixture Church", "Lutheran");
        String auth = "Bearer " + token;

        mockMvc.perform(post("/api/churches/" + churchId + "/favorite").header("Authorization", auth))
            .andExpect(status().isCreated());

        // following is untouched by favoriting
        mockMvc.perform(get("/api/churches/" + churchId + "/follow").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.following").value(false));

        mockMvc.perform(post("/api/churches/" + churchId + "/follow").header("Authorization", auth))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/churches/" + churchId + "/follow").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.following").value(true))
            .andExpect(jsonPath("$.followerCount").value(1));

        // unfavoriting doesn't touch the follow
        mockMvc.perform(delete("/api/churches/" + churchId + "/favorite").header("Authorization", auth))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/churches/" + churchId + "/follow").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.following").value(true));

        mockMvc.perform(get("/api/users/me/following").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Independence Fixture Church"));
    }

    @Test
    void followingTwiceIsAConflictAndUnfollowingATwiceRemovedFollowIs404() throws Exception {
        String token = authToken();
        long churchId = createChurch(token, "Follow Conflict Fixture Church", "Methodist");
        String auth = "Bearer " + token;

        mockMvc.perform(post("/api/churches/" + churchId + "/follow").header("Authorization", auth))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/churches/" + churchId + "/follow").header("Authorization", auth))
            .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/churches/" + churchId + "/follow").header("Authorization", auth))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/churches/" + churchId + "/follow").header("Authorization", auth))
            .andExpect(status().isNotFound());
    }

    @Test
    void favoriteAndFollowRoutesRequireAuthentication() throws Exception {
        String token = authToken();
        long churchId = createChurch(token, "Auth Required Fixture Church", "Catholic");

        mockMvc.perform(get("/api/churches/" + churchId + "/favorite")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/churches/" + churchId + "/favorite")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/churches/" + churchId + "/follow")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/churches/" + churchId + "/follow")).andExpect(status().isUnauthorized());
    }

    @Test
    void viewingAChurchRecordsItAndRepeatViewsUpdateRatherThanDuplicate() throws Exception {
        String token = authToken();
        long churchA = createChurch(token, "Recently Viewed Fixture A", "Presbyterian");
        long churchB = createChurch(token, "Recently Viewed Fixture B", "Presbyterian");
        String auth = "Bearer " + token;

        mockMvc.perform(post("/api/churches/" + churchA + "/view").header("Authorization", auth))
            .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/churches/" + churchB + "/view").header("Authorization", auth))
            .andExpect(status().isNoContent());
        // viewing A again should move it back to the front, not duplicate it
        mockMvc.perform(post("/api/churches/" + churchA + "/view").header("Authorization", auth))
            .andExpect(status().isNoContent());

        String body = mockMvc.perform(get("/api/users/me/recently-viewed").header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode list = objectMapper.readTree(body);
        assertEquals(2, list.size(), "repeat view must not duplicate the row");
        assertEquals("Recently Viewed Fixture A", list.get(0).get("name").asText(), "most recently viewed comes first");
    }

    @Test
    void favoriteSuggestionsPreferTheUsersOwnDenominationSignalAndExcludeAlreadyFavorited() throws Exception {
        String token = authToken();
        String uniqueDenom = "SuggestionFixtureDenom" + userCounter.incrementAndGet();
        long favoritedChurch = createChurch(token, "Suggestion Fixture Favorited", uniqueDenom);
        long matchingChurch = createChurch(token, "Suggestion Fixture Matching", uniqueDenom);
        String auth = "Bearer " + token;

        mockMvc.perform(post("/api/churches/" + favoritedChurch + "/favorite").header("Authorization", auth))
            .andExpect(status().isCreated());

        String body = mockMvc.perform(get("/api/users/me/favorite-suggestions").header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode suggestions = objectMapper.readTree(body);

        boolean containsFavorited = false;
        boolean containsMatching = false;
        for (JsonNode s : suggestions) {
            if (s.get("id").asLong() == favoritedChurch) containsFavorited = true;
            if (s.get("id").asLong() == matchingChurch) containsMatching = true;
        }
        assertTrue(containsMatching, "same-denomination church should be suggested");
        assertTrue(!containsFavorited, "an already-favorited church must never be suggested");
    }
}
