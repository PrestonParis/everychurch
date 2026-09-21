package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;

import com.everychurch.server.dto.LoginRequest;
import com.everychurch.server.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void registerThenLogin_succeeds() throws Exception {
        RegisterRequest register = new RegisterRequest("jane@example.com", "s3cret-password");

        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(register)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.email").value("jane@example.com"))
            .andExpect(jsonPath("$.role").value("MEMBER"));

        LoginRequest login = new LoginRequest("jane@example.com", "s3cret-password");

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(login)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void registerDuplicateEmail_conflict() throws Exception {
        RegisterRequest register = new RegisterRequest("dup@example.com", "password123");

        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(register)))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(register)))
            .andExpect(status().isConflict());
    }

    @Test
    void loginWrongPassword_unauthorized() throws Exception {
        RegisterRequest register = new RegisterRequest("wrongpw@example.com", "correct-password");

        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(register)))
            .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("wrongpw@example.com", "not-the-password");

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(login)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void loginUnknownEmail_unauthorized() throws Exception {
        LoginRequest login = new LoginRequest("nobody@example.com", "whatever");

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(login)))
            .andExpect(status().isUnauthorized());
    }
}
