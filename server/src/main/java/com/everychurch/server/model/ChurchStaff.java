package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "church_staff", uniqueConstraints = @UniqueConstraint(columnNames = {"church_id", "user_id"}))
public class ChurchStaff {

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
    private boolean isOwner = false;

    @ElementCollection
    @CollectionTable(name = "church_staff_permissions", joinColumns = @JoinColumn(name = "church_staff_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission")
    private Set<ChurchPermission> permissions = new HashSet<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Church getChurch() { return church; }
    public void setChurch(Church church) { this.church = church; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public boolean isOwner() { return isOwner; }
    public void setOwner(boolean owner) { this.isOwner = owner; }

    public Set<ChurchPermission> getPermissions() { return permissions; }
    public void setPermissions(Set<ChurchPermission> permissions) { this.permissions = permissions; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean hasPermission(ChurchPermission permission) {
        return isOwner || permissions.contains(permission);
    }
}
