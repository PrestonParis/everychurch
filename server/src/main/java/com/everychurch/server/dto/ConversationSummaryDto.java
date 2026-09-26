package com.everychurch.server.dto;

import java.time.Instant;

/** One row of "who have I DMed", for a conversation-list/inbox view. */
public record ConversationSummaryDto(
    Long otherUserId,
    String otherUserDisplayName,
    String lastMessageBody,
    Instant lastMessageSentAt,
    boolean lastMessageFromMe,
    long unreadCount
) {}
