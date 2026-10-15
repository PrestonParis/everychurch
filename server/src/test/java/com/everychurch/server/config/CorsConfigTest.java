package com.everychurch.server.config;

import com.everychurch.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browsers send an Origin header on same-origin writes. With only localhost:4200
 * allowed, every production login got a plain-text "Invalid CORS request" 403 before
 * reaching AuthController (see WebConfig).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.client-base-url=https://everychurch.co")
class CorsConfigTest extends AbstractIntegrationTest {

    private static final String BAD_LOGIN = "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}";

    @Autowired MockMvc mockMvc;

    @Test
    void loginFromAllowedOrigins_reachesController() throws Exception {
        for (String origin : new String[] {"https://everychurch.co", "https://www.everychurch.co", "http://localhost:4200"}) {
            mockMvc.perform(post("/api/auth/login").header("Origin", origin)
                    .contentType("application/json").content(BAD_LOGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password"));
        }
    }

    @Test
    void unknownOrigin_stillRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login").header("Origin", "https://evil.example")
                .contentType("application/json").content(BAD_LOGIN))
            .andExpect(status().isForbidden());
    }
}
