package com.everychurch.server.dto;

/** GET /api/import-status — public, no auth (see SecurityConfig). Backs the client's
 *  cold-start onboarding screen: churchCount lets it decide whether the directory
 *  looks freshly-empty; the rest reflects any bulk import currently in progress. */
public record ImportStatusDto(
    long churchCount,
    boolean importRunning,
    Integer totalRows,
    int processedRows,
    boolean failed
) {}
