package com.everychurch.server.controller;

import com.everychurch.server.dto.FollowStatusDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.FollowService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

/** Every route here needs a login — SecurityConfig lists /api/churches/{id}/follow as
 *  authenticated ahead of the public GET rule, same treatment as /favorite. */
@RestController
@RequestMapping("/api/churches/{churchId}/follow")
public class FollowController {

    private final FollowService followService;
    private final UserRepository userRepository;

    public FollowController(FollowService followService, UserRepository userRepository) {
        this.followService = followService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public FollowStatusDto status(@PathVariable Long churchId, Authentication authentication) {
        return followService.status(churchId, currentUser(authentication));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void follow(@PathVariable Long churchId, Authentication authentication) {
        followService.follow(churchId, currentUser(authentication));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@PathVariable Long churchId, Authentication authentication) {
        followService.unfollow(churchId, currentUser(authentication));
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

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleConflict(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
