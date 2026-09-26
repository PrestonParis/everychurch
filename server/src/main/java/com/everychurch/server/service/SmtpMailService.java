package com.everychurch.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

/**
 * Plain SMTP (spring-boot-starter-mail's JavaMailSenderImpl, built directly rather than
 * via Spring Boot's mail auto-configuration — same reasoning as GoogleGeocodingService
 * building its own RestClient: one hardcoded-shape client doesn't need the shared
 * autoconfigured bean, and building it here keeps the "degrade if unconfigured" check
 * in one place). Works with any SMTP provider (SES's SMTP interface, SendGrid, Mailtrap
 * for local dev, Gmail with an app password, ...) — see .env.example's MAIL_* variables.
 *
 * Requires MAIL_HOST; when unset, {@link #sendPasswordResetEmail} logs the reset link
 * instead of failing — same pattern as GoogleGeocodingService/S3PresignService for
 * other optional external services. This is deliberately dev-friendly (the link is
 * right there in the server log to paste into a browser) and NOT something to leave
 * unset anywhere a real user could request a reset: nobody outside the server operator
 * ever sees that log line.
 */
@Service
public class SmtpMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailService.class);

    private final String host;
    private final String from;
    private final JavaMailSenderImpl sender;

    public SmtpMailService(
        @Value("${mail.host:}") String host,
        @Value("${mail.port:587}") int port,
        @Value("${mail.username:}") String username,
        @Value("${mail.password:}") String password,
        @Value("${mail.from:no-reply@everychurch.local}") String from
    ) {
        this.host = host;
        this.from = from;

        if (host == null || host.isBlank()) {
            this.sender = null;
            return;
        }

        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(host);
        impl.setPort(port);
        if (username != null && !username.isBlank()) {
            impl.setUsername(username);
            impl.setPassword(password);
            impl.getJavaMailProperties().setProperty("mail.smtp.auth", "true");
        }
        impl.getJavaMailProperties().setProperty("mail.smtp.starttls.enable", "true");
        this.sender = impl;
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetLink) {
        if (sender == null) {
            log.warn(
                "MAIL_HOST is not configured — not sending a real email. Password reset link for {}: {}",
                toEmail, resetLink);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject("Reset your EveryChurch password");
        message.setText(
            "We received a request to reset your EveryChurch password.\n\n"
                + "Reset it here (this link expires in 1 hour): " + resetLink + "\n\n"
                + "If you didn't request this, you can safely ignore this email.");

        try {
            sender.send(message);
        } catch (Exception e) {
            // Sending failure shouldn't surface as a 500 to the person who requested the
            // reset (see AuthController — forgot-password always returns 200 regardless
            // of what happens here, to avoid leaking which emails have accounts). Whoever
            // owns MAIL_HOST needs to notice this in the logs instead.
            log.error("Failed to send password reset email to {} via {}: {}", toEmail, host, e.getMessage());
        }
    }
}
