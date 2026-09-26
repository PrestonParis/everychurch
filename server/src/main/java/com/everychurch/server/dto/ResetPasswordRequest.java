package com.everychurch.server.dto;

public record ResetPasswordRequest(String token, String newPassword) {}
