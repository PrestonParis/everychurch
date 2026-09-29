package com.everychurch.server.controller;

import com.everychurch.server.dto.ChangePasswordRequest;
import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.ChurchStaffDto;
import com.everychurch.server.dto.ClaimDto;
import com.everychurch.server.dto.CoHostInvitationDto;
import com.everychurch.server.dto.ConversationSummaryDto;
import com.everychurch.server.dto.MyProfileDto;
import com.everychurch.server.dto.PostDto;
import com.everychurch.server.dto.PresignedUploadRequest;
import com.everychurch.server.dto.PresignedUploadResponse;
import com.everychurch.server.dto.UpdateAvatarRequest;
import com.everychurch.server.dto.UpdateDisplayNameRequest;
import com.everychurch.server.dto.UpdatePrivacyRequest;
import com.everychurch.server.dto.UpdateProfileRequest;
import com.everychurch.server.dto.UserProfileDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ChurchStaffService;
import com.everychurch.server.service.ChurchViewService;
import static com.everychurch.server.service.ChurchViewService.DEFAULT_RECENT_LIMIT;
import com.everychurch.server.service.ClaimService;
import com.everychurch.server.service.DirectMessageService;
import com.everychurch.server.service.EventService;
import com.everychurch.server.service.FavoriteService;
import com.everychurch.server.service.FollowService;
import com.everychurch.server.service.PostService;
import com.everychurch.server.service.UserProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Self-service "about me" endpoints — everything here reads only the caller's own
 *  data, so none of it needs the per-resource authorization checks the rest of the
 *  API has (see e.g. ChurchStaffController for the church-scoped equivalent). */
@RestController
@RequestMapping("/api/users/me")
public class UserController {

    private final ChurchStaffService churchStaffService;
    private final FavoriteService favoriteService;
    private final DirectMessageService directMessageService;
    private final ChurchViewService churchViewService;
    private final FollowService followService;
    private final PostService postService;
    private final UserProfileService userProfileService;
    private final EventService eventService;
    private final ClaimService claimService;
    private final UserRepository userRepository;

    public UserController(
        ChurchStaffService churchStaffService,
        FavoriteService favoriteService,
        DirectMessageService directMessageService,
        ChurchViewService churchViewService,
        FollowService followService,
        PostService postService,
        UserProfileService userProfileService,
        EventService eventService,
        ClaimService claimService,
        UserRepository userRepository
    ) {
        this.churchStaffService = churchStaffService;
        this.favoriteService = favoriteService;
        this.directMessageService = directMessageService;
        this.churchViewService = churchViewService;
        this.followService = followService;
        this.postService = postService;
        this.userProfileService = userProfileService;
        this.eventService = eventService;
        this.claimService = claimService;
        this.userRepository = userRepository;
    }

    /** Every church the current user is staff at (any permission level), across the
     *  whole app — not scoped to one church the way ChurchStaffController's listing
     *  is. See its own comment for what this powers. */
    @GetMapping("/staff-churches")
    public List<ChurchStaffDto> staffChurches(Authentication authentication) {
        return churchStaffService.listMyStaffChurches(currentUser(authentication));
    }

    @GetMapping("/favorites")
    public List<ChurchDto> favorites(Authentication authentication) {
        return favoriteService.listMyFavorites(currentUser(authentication));
    }

    /** Backs the Favorites page's empty state alongside recentlyViewed — see
     *  FavoriteService.suggestions. */
    @GetMapping("/favorite-suggestions")
    public List<ChurchDto> favoriteSuggestions(Authentication authentication) {
        return favoriteService.suggestions(currentUser(authentication));
    }

    @GetMapping("/recently-viewed")
    public List<ChurchDto> recentlyViewed(
        @RequestParam(required = false) Integer limit,
        Authentication authentication
    ) {
        int effectiveLimit = limit == null ? DEFAULT_RECENT_LIMIT : limit;
        return churchViewService.recentlyViewed(currentUser(authentication), effectiveLimit);
    }

    @GetMapping("/conversations")
    public List<ConversationSummaryDto> conversations(Authentication authentication) {
        return directMessageService.listConversations(currentUser(authentication));
    }

    @GetMapping("/following")
    public List<ChurchDto> following(Authentication authentication) {
        return followService.listMyFollowing(currentUser(authentication));
    }

    /** The "church social media" feed — every post from every church the user
     *  follows, newest first. See PostService.feed for the cursor convention. */
    @GetMapping("/feed")
    public List<PostDto> feed(
        @RequestParam(required = false) Long before,
        @RequestParam(required = false) Integer limit,
        Authentication authentication
    ) {
        return postService.feed(currentUser(authentication), before, limit);
    }

    /** Outstanding "will your church co-host this event?" asks against a church the
     *  caller manages — see EventService.myPendingCoHostInvitations. */
    @GetMapping("/co-host-invitations")
    public List<CoHostInvitationDto> coHostInvitations(Authentication authentication) {
        return eventService.myPendingCoHostInvitations(currentUser(authentication));
    }

    @PutMapping("/display-name")
    public UserProfileDto updateDisplayName(@RequestBody UpdateDisplayNameRequest request, Authentication authentication) {
        return userProfileService.updateDisplayName(currentUser(authentication), request.displayName());
    }

    /** Client uploads the file directly to the returned uploadUrl, then PUTs publicUrl to /avatar. */
    @PostMapping("/avatar/upload-url")
    public PresignedUploadResponse avatarUploadUrl(@RequestBody PresignedUploadRequest request, Authentication authentication) {
        return userProfileService.getAvatarUploadUrl(currentUser(authentication), request.contentType());
    }

    @PutMapping("/avatar")
    public UserProfileDto updateAvatar(@RequestBody UpdateAvatarRequest request, Authentication authentication) {
        return userProfileService.updateAvatar(currentUser(authentication), request.url());
    }

    /** Everything about the caller's own profile — backs the Settings page's initial load. */
    @GetMapping
    public MyProfileDto myProfile(Authentication authentication) {
        return userProfileService.getMyProfile(currentUser(authentication));
    }

    /** One consolidated save for the whole Profile tab (display name, username, bio,
     *  location, tradition, home church) rather than a save button per field. */
    @PutMapping("/profile")
    public MyProfileDto updateProfile(@RequestBody UpdateProfileRequest request, Authentication authentication) {
        return userProfileService.updateProfile(currentUser(authentication), request);
    }

    @PutMapping("/privacy")
    public MyProfileDto updatePrivacy(@RequestBody UpdatePrivacyRequest request, Authentication authentication) {
        return userProfileService.updatePrivacy(currentUser(authentication), request);
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody ChangePasswordRequest request, Authentication authentication) {
        userProfileService.changePassword(currentUser(authentication), request);
    }

    /** Backs the live-typing check in Settings — true if this value is takeable
     *  (including "unchanged from the caller's own current username"). */
    @GetMapping("/username-available")
    public Map<String, Boolean> usernameAvailable(@RequestParam String value, Authentication authentication) {
        return Map.of("available", userProfileService.isUsernameAvailable(value, currentUser(authentication)));
    }

    /** The claims the caller has submitted themselves — distinct from the admin-only
     *  review queue (AdminClaimController), which lists everyone's claims. */
    @GetMapping("/claims")
    public List<ClaimDto> myClaims(Authentication authentication) {
        return claimService.listMine(currentUser(authentication));
    }

    private User currentUser(Authentication authentication) {
        String email = (String) authentication.getPrincipal();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new NoSuchElementException("Authenticated user no longer exists"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(NoSuchElementException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    /** Uploads aren't configured (no S3_BUCKET_NAME) or AWS credentials are missing — a clear error, not a crash. */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleUploadUnavailable(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
