package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.Instant;

/** "Follow this church's updates" — the social-feed relationship (docs/DATA_MODEL.md
 *  `Follow`). Distinct from Favorite (a private bookmark, no feed implications) and
 *  Member (congregational, gates group chat) — a user can be any combination of the
 *  three independently. */
@Entity
@Table(name = "follows", uniqueConstraints = @UniqueConstraint(name = "uq_follows_church_user", columnNames = {"church_id", "user_id"}))
public class Follow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "church_id", nullable = false)
    private Church church;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Church getChurch() { return church; }
    public void setChurch(Church church) { this.church = church; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
