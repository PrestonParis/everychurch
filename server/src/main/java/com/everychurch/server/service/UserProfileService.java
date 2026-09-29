package com.everychurch.server.service;

import com.everychurch.server.dto.ChangePasswordRequest;
import com.everychurch.server.dto.MyProfileDto;
import com.everychurch.server.dto.PresignedUploadResponse;
import com.everychurch.server.dto.UpdatePrivacyRequest;
import com.everychurch.server.dto.UpdateProfileRequest;
import com.everychurch.server.dto.UserProfileDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class UserProfileService {

    private static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(5);
    private static final int MAX_BIO_LENGTH = 240;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_]{3,20}$");
    // "me" specifically would collide with GET /api/users/me at the routing level (see
    // SecurityConfig's ordering comment) — reserved defensively even though routing
    // precedence already protects it, so that username can never be silently unreachable.
    private static final Set<String> RESERVED_USERNAMES = Set.of("me", "admin", "settings", "api");

    private final UserRepository userRepository;
    private final ChurchRepository churchRepository;
    private final S3PresignService s3PresignService;
    private final PasswordEncoder passwordEncoder;

    public UserProfileService(
        UserRepository userRepository,
        ChurchRepository churchRepository,
        S3PresignService s3PresignService,
        PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.churchRepository = churchRepository;
        this.s3PresignService = s3PresignService;
        this.passwordEncoder = passwordEncoder;
    }

    public UserProfileDto updateDisplayName(User user, String displayName) {
        user.setDisplayName(requireDisplayName(displayName));
        return toDto(userRepository.save(user));
    }

    /** Presigned S3 PUT URL for a new avatar — the client uploads directly to S3, then calls updateAvatar() with the result. */
    public PresignedUploadResponse getAvatarUploadUrl(User user, String contentType) {
        return s3PresignService.presignPut("avatars/" + user.getId(), contentType, UPLOAD_URL_EXPIRY);
    }

    /** Blank/null url clears the avatar (the Settings page's "Remove photo" control). */
    public UserProfileDto updateAvatar(User user, String url) {
        user.setAvatarUrl(url == null || url.isBlank() ? null : url);
        return toDto(userRepository.save(user));
    }

    public MyProfileDto getMyProfile(User user) {
        return toMyProfileDto(user);
    }

    /** One consolidated save for the whole Profile tab (display name, username, bio,
     *  location, tradition, home church) rather than a save per field. */
    public MyProfileDto updateProfile(User user, UpdateProfileRequest request) {
        user.setDisplayName(requireDisplayName(request.displayName()));
        user.setUsername(normalizeUsername(request.username(), user));
        user.setBio(trimBio(request.bio()));
        user.setLocation(blankToNull(request.location()));
        user.setTradition(blankToNull(request.tradition()));
        user.setHomeChurch(resolveHomeChurch(request.homeChurchId()));
        return toMyProfileDto(userRepository.save(user));
    }

    public MyProfileDto updatePrivacy(User user, UpdatePrivacyRequest request) {
        user.setProfileVisibility(request.profileVisibility());
        user.setShowLocation(request.showLocation());
        user.setShowTradition(request.showTradition());
        user.setShowHomeChurch(request.showHomeChurch());
        user.setShowFollowing(request.showFollowing());
        return toMyProfileDto(userRepository.save(user));
    }

    public void changePassword(User user, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.currentPassword() == null ? "" : request.currentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (request.newPassword() == null || request.newPassword().length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    /** Backs the live-typing check in Settings — true if nobody else (or nobody at
     *  all) already holds this username, case-insensitively. */
    public boolean isUsernameAvailable(String username, User requester) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) return false;
        if (RESERVED_USERNAMES.contains(username.toLowerCase())) return false;
        return userRepository.findByUsernameIgnoreCase(username)
            .map(existing -> existing.getId().equals(requester.getId()))
            .orElse(true);
    }

    private String normalizeUsername(String username, User user) {
        String trimmed = username == null ? "" : username.trim().toLowerCase();
        if (trimmed.isEmpty()) return null;
        if (!USERNAME_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Username must be 3-20 characters: lowercase letters, numbers, and underscores only");
        }
        if (RESERVED_USERNAMES.contains(trimmed)) {
            throw new IllegalArgumentException("That username is reserved");
        }
        // IllegalArgumentException (→ 400), not IllegalStateException — UserController's
        // IllegalStateException handler is reserved for "uploads aren't configured" (→ 503);
        // reusing it here would report a username conflict as a server outage.
        userRepository.findByUsernameIgnoreCase(trimmed).ifPresent(existing -> {
            if (!existing.getId().equals(user.getId())) {
                throw new IllegalArgumentException("That username is already taken");
            }
        });
        return trimmed;
    }

    private String trimBio(String bio) {
        if (bio == null) return null;
        String trimmed = bio.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_BIO_LENGTH ? trimmed.substring(0, MAX_BIO_LENGTH) : trimmed;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Church resolveHomeChurch(Long homeChurchId) {
        if (homeChurchId == null) return null;
        return churchRepository.findById(homeChurchId)
            .orElseThrow(() -> new NoSuchElementException("Church not found: " + homeChurchId));
    }

    /** Same requirement as registration (see AuthService.requireDisplayName) — a user can rename themselves, but not to blank. */
    private String requireDisplayName(String displayName) {
        String trimmed = displayName == null ? "" : displayName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A display name is required");
        }
        return trimmed;
    }

    private UserProfileDto toDto(User user) {
        return new UserProfileDto(user.getId(), user.getEmail(), user.getRole(), user.getDisplayName(), user.getAvatarUrl());
    }

    private MyProfileDto toMyProfileDto(User user) {
        Church home = user.getHomeChurch();
        return new MyProfileDto(
            user.getId(), user.getEmail(), user.getRole(), user.getDisplayName(), user.getAvatarUrl(),
            user.getUsername(), user.getBio(), user.getLocation(), user.getTradition(),
            home == null ? null : home.getId(), home == null ? null : home.getName(),
            user.getProfileVisibility(),
            user.isShowLocation(), user.isShowTradition(), user.isShowHomeChurch(), user.isShowFollowing()
        );
    }
}
