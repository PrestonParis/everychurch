package com.everychurch.server.dto;

/** What the current user is to one church, so the client knows which buttons/panels to show. */
public record MembershipStatusDto(boolean member, boolean staff, boolean canChat) {}
