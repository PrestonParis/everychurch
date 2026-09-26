package com.everychurch.server.service;

/** Outbound transactional email. See SmtpMailService for the one implementation and
 *  its graceful-degradation behavior when no mail server is configured. */
public interface MailService {
    void sendPasswordResetEmail(String toEmail, String resetLink);
}
