package com.everychurch.server.dto;

/** Only ever reflects whether the CALLER has blocked the other user — never whether
 *  the other user has blocked the caller, which stays private (see
 *  DirectMessageService.requireNotBlocked's comment on why send() answers a block in
 *  either direction with the same generic rejection instead). */
public record BlockStatusDto(boolean blocked) {}
