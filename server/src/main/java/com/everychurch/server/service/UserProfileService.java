package com.everychurch.server.service;

import com.everychurch.server.dto.PresignedUploadResponse;
import com.everychurch.server.dto.UserProfileDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class UserProfileService {

    private static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final S3PresignService s3PresignService;

    public UserProfileService(UserRepository userRepository, S3PresignService s3PresignService) {
        this.userRepository = userRepository;
        this.s3PresignService = s3PresignService;
    }

    public UserProfileDto updateDisplayName(User user, String displayName) {
        user.setDisplayName(requireDisplayName(displayName));
        return toDto(userRepository.save(user));
    }

    /** Presigned S3 PUT URL for a new avatar — the client uploads directly to S3, then calls updateAvatar() with the result. */
    public PresignedUploadResponse getAvatarUploadUrl(User user, String contentType) {
        return s3PresignService.presignPut("avatars/" + user.getId(), contentType, UPLOAD_URL_EXPIRY);
    }

    public UserProfileDto updateAvatar(User user, String url) {
        user.setAvatarUrl(url);
        return toDto(userRepository.save(user));
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
}
