package com.everychurch.server.dto;

import java.time.Instant;
import java.util.Set;

public record EventDto(
    Long id,
    String title,
    String description,
    Instant startsAt,
    Instant endsAt,
    Set<Long> churchIds
) {}
