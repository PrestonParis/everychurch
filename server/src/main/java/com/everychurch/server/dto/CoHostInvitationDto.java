package com.everychurch.server.dto;

import java.time.Instant;

/** One outstanding "will your church co-host this event?" invitation — from
 *  GET /api/users/me/co-host-invitations, scoped to churches the caller manages. */
public record CoHostInvitationDto(
    Long eventId, String eventTitle, Instant eventStartsAt,
    Long churchId, String churchName,
    Instant createdAt
) {}
