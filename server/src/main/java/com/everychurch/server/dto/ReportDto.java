package com.everychurch.server.dto;

import java.time.Instant;

public record ReportDto(
    Long id,
    Long reporterId, String reporterDisplayName,
    String targetType, Long targetId, String targetLabel,
    String reason,
    String status,
    Instant createdAt,
    Instant resolvedAt, String resolvedByDisplayName
) {}
