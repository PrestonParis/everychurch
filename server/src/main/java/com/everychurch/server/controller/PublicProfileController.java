package com.everychurch.server.controller;

import com.everychurch.server.dto.PublicUserProfileDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.PublicProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/** GET /api/users/{username} — a user's public profile page, reachable logged-out.
 *  See SecurityConfig for why this is permitAll on a single-segment path ahead of
 *  the authenticated "/api/users/me/**" rules. */
@RestController
public class PublicProfileController {

    private final PublicProfileService publicProfileService;
    private final UserRepository userRepository;

    public PublicProfileController(PublicProfileService publicProfileService, UserRepository userRepository) {
        this.publicProfileService = publicProfileService;
        this.userRepository = userRepository;
    }

    @GetMapping("/api/users/{username}")
    public PublicUserProfileDto get(@PathVariable String username, Authentication authentication) {
        return publicProfileService.getByUsername(username, currentUserOrNull(authentication));
    }

    /** Unlike UserController's currentUser() helper, this path is reachable while
     *  logged out (permitAll) — Authentication is null there, not merely
     *  unauthenticated, since anonymous auth is disabled app-wide (see SecurityConfig). */
    private User currentUserOrNull(Authentication authentication) {
        if (authentication == null) return null;
        String email = (String) authentication.getPrincipal();
        return userRepository.findByEmail(email).orElse(null);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(NoSuchElementException e) {
        return Map.of("error", e.getMessage());
    }
}
