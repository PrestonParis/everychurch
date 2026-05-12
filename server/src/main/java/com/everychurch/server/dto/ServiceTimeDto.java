package com.everychurch.server.dto;

public record ServiceTimeDto(
    Long id,
    String dayOfWeek,
    String time,
    String type,
    String language
) {}
