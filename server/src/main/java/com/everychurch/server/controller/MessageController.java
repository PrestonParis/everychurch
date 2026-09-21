package com.everychurch.server.controller;

import com.everychurch.server.dto.MessageDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Chat history over plain REST. WebSocket (ChatController) is for live delivery only —
 * loading what was said before you opened the chat is an ordinary paged GET.
 * SecurityConfig lists /api/churches/{id}/messages/** as authenticated.
 */
@RestController
@RequestMapping("/api/churches/{churchId}/messages")
public class MessageController {

    private final ChatService chatService;
    private final UserRepository userRepository;

    public MessageController(ChatService chatService, UserRepository userRepository) {
        this.chatService = chatService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<MessageDto> history(
        @PathVariable Long churchId,
        @RequestParam(required = false) Long before,
        @RequestParam(required = false) Integer limit,
        Authentication authentication
    ) {
        String email = (String) authentication.getPrincipal();
        User requester = userRepository.findByEmail(email)
            .orElseThrow(() -> new NoSuchElementException("Authenticated user no longer exists"));
        return chatService.history(churchId, requester, before, limit);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(NoSuchElementException e) {
        return Map.of("error", e.getMessage());
    }
}
