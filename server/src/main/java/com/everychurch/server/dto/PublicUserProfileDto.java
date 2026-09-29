package com.everychurch.server.dto;

import java.util.List;

/** GET /api/users/{username} — nullable fields are omitted server-side per the
 *  viewed user's own show-flags (see PublicProfileService), not just hidden by the
 *  client, so private data never leaves the server in the first place. */
public record PublicUserProfileDto(
    Long id,
    String username,
    String displayName,
    String avatarUrl,
    String bio,
    String location,
    String tradition,
    Long homeChurchId,
    String homeChurchName,
    List<ChurchDto> following,
    boolean isSelf
) {}
