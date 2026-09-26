package com.everychurch.server.controller;

import com.everychurch.server.AbstractIntegrationTest;
import com.everychurch.server.dto.ForgotPasswordRequest;
import com.everychurch.server.dto.LoginRequest;
import com.everychurch.server.dto.RegisterRequest;
import com.everychurch.server.dto.ResetPasswordRequest;
import com.everychurch.server.service.MailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/auth/forgot-password + /api/auth/reset-password. Replaces the real
 * SmtpMailService with a fake that captures the link instead of sending it, so the
 * raw token (never persisted — only its hash is, see PasswordResetToken) can be
 * pulled out the same way a person would from their inbox.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MailService mailService;

    private FakeMailService fakeMailService() {
        return (FakeMailService) mailService;
    }

    @BeforeEach
    void resetFake() {
        fakeMailService().lastToEmail = null;
        fakeMailService().lastResetLink = null;
    }

    private void register(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, "Test User"))))
            .andExpect(status().isCreated());
    }

    private String tokenFromLastLink() {
        String link = fakeMailService().lastResetLink;
        assertNotNull(link, "expected a reset link to have been sent");
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    @Test
    void forgotPasswordForKnownEmail_sendsAResetLinkAndResetWorks() throws Exception {
        register("reset-happy@example.com", "original-password");

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new ForgotPasswordRequest("reset-happy@example.com"))))
            .andExpect(status().isOk());

        assertEquals("reset-happy@example.com", fakeMailService().lastToEmail);
        String token = tokenFromLastLink();

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new ResetPasswordRequest(token, "brand-new-password"))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new LoginRequest("reset-happy@example.com", "brand-new-password"))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new LoginRequest("reset-happy@example.com", "original-password"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void forgotPasswordForUnknownEmail_stillReturns200ButSendsNothing() throws Exception {
        // No 404/leak here on purpose — see AuthService.forgotPassword's comment.
        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new ForgotPasswordRequest("nobody-at-all@example.com"))))
            .andExpect(status().isOk());

        assertNull(fakeMailService().lastResetLink);
    }

    @Test
    void resetPasswordWithAnInvalidToken_isRejected() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new ResetPasswordRequest("not-a-real-token", "whatever-password"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void resetPasswordToken_cannotBeReusedASecondTime() throws Exception {
        register("reset-reuse@example.com", "original-password");

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new ForgotPasswordRequest("reset-reuse@example.com"))))
            .andExpect(status().isOk());
        String token = tokenFromLastLink();

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new ResetPasswordRequest(token, "first-new-password"))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new ResetPasswordRequest(token, "second-new-password"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void requestingASecondResetLink_invalidatesTheFirstOne() throws Exception {
        register("reset-superseded@example.com", "original-password");

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new ForgotPasswordRequest("reset-superseded@example.com"))))
            .andExpect(status().isOk());
        String firstToken = tokenFromLastLink();

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new ForgotPasswordRequest("reset-superseded@example.com"))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new ResetPasswordRequest(firstToken, "wont-take-effect"))))
            .andExpect(status().isBadRequest());
    }

    @TestConfiguration
    static class FakeMailConfig {
        @Bean
        @Primary
        MailService fakeMailService() {
            return new FakeMailService();
        }
    }

    static class FakeMailService implements MailService {
        volatile String lastToEmail;
        volatile String lastResetLink;

        @Override
        public void sendPasswordResetEmail(String toEmail, String resetLink) {
            this.lastToEmail = toEmail;
            this.lastResetLink = resetLink;
        }
    }
}
