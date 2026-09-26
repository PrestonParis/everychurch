package com.everychurch.server.controller;

import com.everychurch.server.dto.BlockStatusDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.BlockService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

/** Every route here needs a login — not covered by any public rule in SecurityConfig,
 *  so the blanket "everything else requires authentication" applies. */
@RestController
@RequestMapping("/api/users/{userId}/block")
public class BlockController {

    private final BlockService blockService;
    private final UserRepository userRepository;

    public BlockController(BlockService blockService, UserRepository userRepository) {
        this.blockService = blockService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public BlockStatusDto status(@PathVariable Long userId, Authentication authentication) {
        return blockService.status(currentUser(authentication), userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void block(@PathVariable Long userId, Authentication authentication) {
        blockService.block(currentUser(authentication), userId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable Long userId, Authentication authentication) {
        blockService.unblock(currentUser(authentication), userId);
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

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleConflict(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
