package com.everychurch.server.dto;

/** PUT /api/users/me/profile — one consolidated save for the whole Profile tab
 *  rather than a save button per field. homeChurchId null clears it. */
public record UpdateProfileRequest(
    String displayName,
    String username,
    String bio,
    String location,
    String tradition,
    Long homeChurchId
) {}
