package com.everychurch.server.dto;

import com.everychurch.server.model.ProfileVisibility;
import com.everychurch.server.model.Role;

/** Everything about the authenticated caller's own profile — GET /api/users/me. */
public record MyProfileDto(
    Long id,
    String email,
    Role role,
    String displayName,
    String avatarUrl,
    String username,
    String bio,
    String location,
    String tradition,
    Long homeChurchId,
    String homeChurchName,
    ProfileVisibility profileVisibility,
    boolean showLocation,
    boolean showTradition,
    boolean showHomeChurch,
    boolean showFollowing
) {}
