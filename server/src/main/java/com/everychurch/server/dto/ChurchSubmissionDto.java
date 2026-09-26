package com.everychurch.server.dto;

import com.everychurch.server.model.ChurchSubmissionStatus;

import java.time.Instant;
import java.util.List;

public record ChurchSubmissionDto(
    Long id,
    Long targetChurchId,
    String targetChurchName,
    ChurchDto proposedChanges,
    String submittedBy,
    ChurchSubmissionStatus status,
    String reviewedByEmail,
    Instant createdAt,
    Instant reviewedAt,
    // Empty for anything already decided — only worth computing while a human still has
    // a decision to make. See ChurchService.findPossibleDuplicates.
    List<ChurchDto> possibleDuplicates
) {}
