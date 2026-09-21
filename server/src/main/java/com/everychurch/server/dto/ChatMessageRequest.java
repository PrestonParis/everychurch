package com.everychurch.server.dto;

/** Payload of a STOMP SEND to /app/churches/{churchId}/chat.send. */
public record ChatMessageRequest(String body) {}
