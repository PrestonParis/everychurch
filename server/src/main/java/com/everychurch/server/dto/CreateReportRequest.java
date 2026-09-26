package com.everychurch.server.dto;

/** targetType is "USER" or "CHURCH" (ReportTargetType). */
public record CreateReportRequest(String targetType, Long targetId, String reason) {}
