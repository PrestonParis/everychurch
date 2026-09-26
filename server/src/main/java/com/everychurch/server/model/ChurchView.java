package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.Instant;

/** One row per (church, user): "the last time this user looked at this church's
 *  profile", not a full visit log — see V12__add_church_views.sql. Feeds the
 *  Favorites page's "recently viewed" section and its suggestion heuristic. */
@Entity
@Table(name = "church_views", uniqueConstraints = @UniqueConstraint(name = "uq_church_views_church_user", columnNames = {"church_id", "user_id"}))
public class ChurchView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "church_id", nullable = false)
    private Church church;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Instant viewedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Church getChurch() { return church; }
    public void setChurch(Church church) { this.church = church; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Instant getViewedAt() { return viewedAt; }
    public void setViewedAt(Instant viewedAt) { this.viewedAt = viewedAt; }
}
