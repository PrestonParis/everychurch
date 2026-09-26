package com.everychurch.server.dto;

/** Payload of a STOMP SEND to /app/direct-messages/{recipientId}/send. */
public record DirectMessageRequest(String body) {}
