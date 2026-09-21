package com.everychurch.server.dto;

import java.time.Instant;

/** senderDisplayName is the email's local part, same as MemberDto — never the full address. */
public record MessageDto(Long id, Long churchId, String senderDisplayName, String body, Instant sentAt) {}
