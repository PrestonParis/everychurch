package com.everychurch.server.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    @JsonIgnore
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Chosen at registration (required there — see AuthService). Nullable only because
     *  accounts created before this field existed have none; getDisplayName() falls back
     *  for those rather than needing a backfill migration. */
    private String displayName;

    private String avatarUrl;

    /** Unique (case-insensitively — see V20's partial index), nullable: not every
     *  account has claimed one yet. Backs the public profile URL (/users/{username}). */
    private String username;

    private String bio;

    /** Free-text city/region the user chooses to show — see showLocation. */
    private String location;

    /** User-declared tradition/denomination (one of DENOMINATIONS.ts's labels, or
     *  blank for "prefer not to say") — never inferred from quiz results. */
    private String tradition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_church_id")
    private Church homeChurch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProfileVisibility profileVisibility = ProfileVisibility.PUBLIC;

    @Column(nullable = false)
    private boolean showLocation = false;

    @Column(nullable = false)
    private boolean showTradition = true;

    @Column(nullable = false)
    private boolean showHomeChurch = true;

    @Column(nullable = false)
    private boolean showFollowing = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getDisplayNameRaw() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getTradition() { return tradition; }
    public void setTradition(String tradition) { this.tradition = tradition; }

    public Church getHomeChurch() { return homeChurch; }
    public void setHomeChurch(Church homeChurch) { this.homeChurch = homeChurch; }

    public ProfileVisibility getProfileVisibility() { return profileVisibility; }
    public void setProfileVisibility(ProfileVisibility profileVisibility) { this.profileVisibility = profileVisibility; }

    public boolean isShowLocation() { return showLocation; }
    public void setShowLocation(boolean showLocation) { this.showLocation = showLocation; }

    public boolean isShowTradition() { return showTradition; }
    public void setShowTradition(boolean showTradition) { this.showTradition = showTradition; }

    public boolean isShowHomeChurch() { return showHomeChurch; }
    public void setShowHomeChurch(boolean showHomeChurch) { this.showHomeChurch = showHomeChurch; }

    public boolean isShowFollowing() { return showFollowing; }
    public void setShowFollowing(boolean showFollowing) { this.showFollowing = showFollowing; }

    /** What other congregants see (chat, member list). The chosen name if set; the
     *  email's local part as a fallback for accounts from before this field existed. */
    public String getDisplayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
