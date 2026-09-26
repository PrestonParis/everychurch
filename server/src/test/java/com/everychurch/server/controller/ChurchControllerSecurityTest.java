package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;

import com.everychurch.server.dto.RegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms the SecurityConfig wiring from Prompt 6: GET stays public, writes
 * require a valid JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChurchControllerSecurityTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void listChurches_publicNoAuthRequired() throws Exception {
        mockMvc.perform(get("/api/churches"))
            .andExpect(status().isOk());
    }

    @Test
    void createChurchWithoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(post("/api/churches")
                .contentType("application/json")
                .content("{\"name\":\"Test Church\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void createChurchWithInvalidToken_isUnauthorized() throws Exception {
        mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer not-a-real-token")
                .contentType("application/json")
                .content("{\"name\":\"Test Church\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void createChurchWithValidToken_isAuthorized() throws Exception {
        RegisterRequest register = new RegisterRequest("churchcreator@example.com", "password123", "Test User");

        String registerBody = mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(register)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(registerBody);
        String token = json.get("token").asText();

        mockMvc.perform(post("/api/churches")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"name\":\"Test Church\"}"))
            .andExpect(status().isCreated());
    }
}
