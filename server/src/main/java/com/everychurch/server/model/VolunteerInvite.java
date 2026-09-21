package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "volunteer_invites")
public class VolunteerInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VolunteerInviteStatus status = VolunteerInviteStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "redeemed_by_id")
    private User redeemedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant expiresAt;

    private Instant redeemedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public VolunteerInviteStatus getStatus() { return status; }
    public void setStatus(VolunteerInviteStatus status) { this.status = status; }

    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }

    public User getRedeemedBy() { return redeemedBy; }
    public void setRedeemedBy(User redeemedBy) { this.redeemedBy = redeemedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Instant redeemedAt) { this.redeemedAt = redeemedAt; }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }
}
