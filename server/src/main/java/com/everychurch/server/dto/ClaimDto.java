package com.everychurch.server.dto;

import com.everychurch.server.model.ClaimStatus;

import java.time.Instant;

public record ClaimDto(
    Long id,
    Long churchId,
    String churchName,
    String requesterEmail,
    ClaimStatus status,
    String notes,
    String decidedByEmail,
    Instant createdAt,
    Instant decidedAt
) {}
