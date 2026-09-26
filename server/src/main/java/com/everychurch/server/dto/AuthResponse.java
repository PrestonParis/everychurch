package com.everychurch.server.dto;

import com.everychurch.server.model.Role;

public record AuthResponse(String token, Long id, String email, Role role, String displayName, String avatarUrl) {}
