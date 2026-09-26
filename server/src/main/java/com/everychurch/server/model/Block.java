package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.Instant;

/** One user blocking another — currently only enforced against direct messages
 *  (DirectMessageService.requireNotBlocked): once either side has a Block row against
 *  the other, neither can send the other a new DM. Group chat and posts aren't
 *  gated on this (see docs/ARCHITECTURE.md "Known limitations" under Chat for the
 *  moderation gaps that remain). */
@Entity
@Table(name = "blocks", uniqueConstraints = @UniqueConstraint(name = "uq_blocks_blocker_blocked", columnNames = {"blocker_id", "blocked_id"}))
public class Block {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocker_id", nullable = false)
    private User blocker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocked_id", nullable = false)
    private User blocked;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getBlocker() { return blocker; }
    public void setBlocker(User blocker) { this.blocker = blocker; }

    public User getBlocked() { return blocked; }
    public void setBlocked(User blocked) { this.blocked = blocked; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
