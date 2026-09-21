package com.everychurch.server.dto;

import com.everychurch.server.model.Role;

public record AuthResponse(String token, String email, Role role) {}
