package com.everychurch.server.service;

import com.everychurch.server.dto.PublicUserProfileDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/** GET /api/users/{username} — the one place a user's public identity is assembled,
 *  separate from UserProfileService's "me"-scoped self-service methods. */
@Service
public class PublicProfileService {

    private static final String UNAVAILABLE = "This profile isn't available";

    private final UserRepository userRepository;
    private final FollowService followService;

    public PublicProfileService(UserRepository userRepository, FollowService followService) {
        this.userRepository = userRepository;
        this.followService = followService;
    }

    /** viewer is null for a logged-out visitor. Throws NoSuchElementException — mapped
     *  to 404 — both when the username doesn't exist AND when visibility excludes the
     *  viewer, with the identical message either way, so a private username can't be
     *  distinguished from one that was never registered. */
    @Transactional(readOnly = true)
    public PublicUserProfileDto getByUsername(String username, User viewer) {
        User user = userRepository.findByUsernameIgnoreCase(username)
            .orElseThrow(() -> new NoSuchElementException(UNAVAILABLE));

        boolean isSelf = viewer != null && viewer.getId().equals(user.getId());
        if (!isSelf && !isVisibleTo(user, viewer)) {
            throw new NoSuchElementException(UNAVAILABLE);
        }

        Church home = user.getHomeChurch();
        return new PublicUserProfileDto(
            user.getId(),
            user.getUsername(),
            user.getDisplayName(),
            user.getAvatarUrl(),
            user.getBio(),
            (isSelf || user.isShowLocation()) ? user.getLocation() : null,
            (isSelf || user.isShowTradition()) ? user.getTradition() : null,
            (isSelf || user.isShowHomeChurch()) && home != null ? home.getId() : null,
            (isSelf || user.isShowHomeChurch()) && home != null ? home.getName() : null,
            (isSelf || user.isShowFollowing()) ? followService.listMyFollowing(user) : null,
            isSelf
        );
    }

    private boolean isVisibleTo(User user, User viewer) {
        return switch (user.getProfileVisibility()) {
            case PUBLIC -> true;
            case MEMBERS_ONLY -> viewer != null;
            case PRIVATE -> false;
        };
    }
}
