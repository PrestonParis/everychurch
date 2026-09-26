package com.everychurch.server.controller;

import com.everychurch.server.dto.DirectMessageDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.DirectMessageService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Direct-message history over plain REST — WebSocket (DirectChatController) is for
 * live delivery only, same split as the group chat's MessageController. Every route
 * here is implicitly authenticated: it's not covered by any public rule in
 * SecurityConfig, so the blanket "everything else requires authentication" applies.
 */
@RestController
@RequestMapping("/api/direct-messages")
public class DirectMessageController {

    private final DirectMessageService directMessageService;
    private final UserRepository userRepository;

    public DirectMessageController(DirectMessageService directMessageService, UserRepository userRepository) {
        this.directMessageService = directMessageService;
        this.userRepository = userRepository;
    }

    @GetMapping("/{otherUserId}")
    public List<DirectMessageDto> history(
        @PathVariable Long otherUserId,
        @RequestParam(required = false) Long before,
        @RequestParam(required = false) Integer limit,
        Authentication authentication
    ) {
        return directMessageService.history(currentUser(authentication), otherUserId, before, limit);
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
}
