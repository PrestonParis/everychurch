package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One church's participation in one event, with its own consent state — replaces a
 * plain many-to-many join (found in review, 2026-09-23): an organizer with
 * MANAGE_EVENTS on just ONE participating church used to be able to list ANY other
 * church as a co-host with no say from that church at all. A church the requester can
 * actually manage goes straight to CONFIRMED; any other named church starts PENDING
 * and only becomes CONFIRMED (or DECLINED) when one of ITS OWN MANAGE_EVENTS staff
 * responds (EventService.respondToCoHost). Public-facing displays only ever show
 * CONFIRMED rows (EventDto.churches) — PENDING ones are visible only to the event's
 * managers and to the invited church's own staff (EventDto.pendingChurches,
 * UserController.coHostInvitations).
 */
@Entity
@Table(name = "event_co_hosts", uniqueConstraints = @UniqueConstraint(name = "uq_event_co_hosts_event_church", columnNames = {"event_id", "church_id"}))
public class EventCoHost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "church_id", nullable = false)
    private Church church;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventCoHostStatus status = EventCoHostStatus.CONFIRMED;

    /** Who added this church to the event — null for rows backfilled from the old
     *  event_churches table (V18), which predates tracking this. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_id")
    private User invitedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant respondedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Event getEvent() { return event; }
    public void setEvent(Event event) { this.event = event; }

    public Church getChurch() { return church; }
    public void setChurch(Church church) { this.church = church; }

    public EventCoHostStatus getStatus() { return status; }
    public void setStatus(EventCoHostStatus status) { this.status = status; }

    public User getInvitedBy() { return invitedBy; }
    public void setInvitedBy(User invitedBy) { this.invitedBy = invitedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getRespondedAt() { return respondedAt; }
    public void setRespondedAt(Instant respondedAt) { this.respondedAt = respondedAt; }
}
