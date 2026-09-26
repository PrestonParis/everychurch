package com.everychurch.server.dto;

import java.time.Instant;

public record PostDto(
    Long id, Long churchId, String churchName, Long authorId, String authorDisplayName,
    String body, String imageUrl, Instant createdAt
) {}
