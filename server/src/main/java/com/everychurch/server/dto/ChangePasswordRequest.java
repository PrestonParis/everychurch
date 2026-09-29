package com.everychurch.server.dto;

public record ChangePasswordRequest(String currentPassword, String newPassword) {}
