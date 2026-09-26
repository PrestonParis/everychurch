package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.Instant;

/** Purely personal (docs/DATA_MODEL.md `Favorite`) — unlike Member/ChurchStaff, nobody
 *  but the favoriting user ever sees this row. */
@Entity
@Table(name = "favorites", uniqueConstraints = @UniqueConstraint(name = "uq_favorites_church_user", columnNames = {"church_id", "user_id"}))
public class Favorite {

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
