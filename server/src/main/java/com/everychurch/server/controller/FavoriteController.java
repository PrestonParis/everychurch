package com.everychurch.server.controller;

import com.everychurch.server.dto.FavoriteStatusDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.FavoriteService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

/** Every route here needs a login — SecurityConfig lists /api/churches/{id}/favorite as
 *  authenticated ahead of the public GET rule, same treatment as /members and /messages. */
@RestController
@RequestMapping("/api/churches/{churchId}/favorite")
public class FavoriteController {

    private final FavoriteService favoriteService;
    private final UserRepository userRepository;

    public FavoriteController(FavoriteService favoriteService, UserRepository userRepository) {
        this.favoriteService = favoriteService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public FavoriteStatusDto status(@PathVariable Long churchId, Authentication authentication) {
        return favoriteService.status(churchId, currentUser(authentication));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void favorite(@PathVariable Long churchId, Authentication authentication) {
        favoriteService.favorite(churchId, currentUser(authentication));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfavorite(@PathVariable Long churchId, Authentication authentication) {
        favoriteService.unfavorite(churchId, currentUser(authentication));
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
