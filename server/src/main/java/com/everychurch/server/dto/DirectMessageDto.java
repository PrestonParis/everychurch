package com.everychurch.server.dto;

import java.time.Instant;

public record DirectMessageDto(
    Long id, Long senderId, Long recipientId, String senderDisplayName, String body, Instant sentAt
) {}
