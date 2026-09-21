package com.everychurch.server.dto;

/** expiresInHours is nullable — null means the invite never expires. */
public record CreateInviteRequest(Long expiresInHours) {}
