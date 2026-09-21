package com.everychurch.server.dto;

import com.everychurch.server.model.ChurchSubmissionStatus;

import java.time.Instant;

public record ChurchSubmissionDto(
    Long id,
    Long targetChurchId,
    String targetChurchName,
    ChurchDto proposedChanges,
    String submittedBy,
    ChurchSubmissionStatus status,
    String reviewedByEmail,
    Instant createdAt,
    Instant reviewedAt
) {}
