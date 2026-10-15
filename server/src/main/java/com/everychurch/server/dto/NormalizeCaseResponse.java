package com.everychurch.server.dto;

/** {@code nextAfterId} is null once the last church has been scanned. */
public record NormalizeCaseResponse(int scanned, int updated, Long nextAfterId) {}
