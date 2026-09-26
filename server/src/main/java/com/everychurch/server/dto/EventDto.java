package com.everychurch.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record EventDto(
    Long id,
    String title,
    String description,
    Instant startsAt,
    Instant endsAt,
    // CONFIRMED + PENDING church ids (not DECLINED) — what the edit form preselects,
    // so re-saving an event doesn't silently drop a still-pending invitation.
    Set<Long> churchIds,
    // CONFIRMED only — added alongside churchIds (kept for existing callers, e.g.
    // event-form's preselection) rather than replacing it. This is the public-facing
    // "hosted by" list: a PENDING co-host hasn't agreed to anything yet, so it never
    // appears here (see EventCoHost's class comment — co-host consent, found in review).
    List<ChurchRefDto> churches,
    // PENDING only — shown to the event's own managers (and to the invited church's
    // staff, who see it via UserController.coHostInvitations instead) so it's visible
    // that an invitation is still outstanding, without implying the church signed on.
    List<ChurchRefDto> pendingChurches
) {}
