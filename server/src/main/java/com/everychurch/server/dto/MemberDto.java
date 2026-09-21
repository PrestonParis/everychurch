package com.everychurch.server.dto;

import java.time.Instant;

/**
 * displayName is the local part of the member's email, deliberately not the full
 * address: a member list is visible to every other member of the church, and
 * exposing everyone's email to their whole congregation isn't needed for networking.
 */
public record MemberDto(Long id, Long churchId, String displayName, Instant joinedAt) {}
