package com.everychurch.server.dto;

import com.everychurch.server.model.VolunteerInviteStatus;

import java.time.Instant;

public record VolunteerInviteResponse(
    Long id,
    String code,
    VolunteerInviteStatus status,
    Instant createdAt,
    Instant expiresAt,
    Instant redeemedAt
) {}
