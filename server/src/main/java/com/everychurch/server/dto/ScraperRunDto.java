package com.everychurch.server.dto;

import com.everychurch.server.model.ScraperRunStatus;

import java.time.Instant;

public record ScraperRunDto(
    Long id,
    String region,
    Instant startedAt,
    Instant completedAt,
    Integer discovered,
    Integer newCount,
    Integer accepted,
    ScraperRunStatus status
) {}
