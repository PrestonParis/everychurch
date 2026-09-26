package com.everychurch.server.dto;

import com.everychurch.server.model.Role;

/** Returned by the display-name/avatar mutation endpoints in UserController so the
 *  client can refresh its cached identity without a separate "who am I" round-trip. */
public record UserProfileDto(Long id, String email, Role role, String displayName, String avatarUrl) {}
