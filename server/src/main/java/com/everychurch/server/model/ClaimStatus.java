package com.everychurch.server.model;

public enum ClaimStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /** Was APPROVED; the owner gave the church up or a Platform Admin took it back. */
    RELEASED
}
