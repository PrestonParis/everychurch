package com.everychurch.server.dto;

import com.everychurch.server.model.ScraperRunStatus;

public record CompleteScraperRunRequest(
    ScraperRunStatus status,
    Integer discovered,
    Integer newCount,
    Integer accepted
) {}
