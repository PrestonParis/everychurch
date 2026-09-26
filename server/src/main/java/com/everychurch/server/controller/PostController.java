package com.everychurch.server.controller;

import com.everychurch.server.dto.CreatePostRequest;
import com.everychurch.server.dto.PostDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.PostService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * A church's posts — public to read (same as events/sermons), gated to
 * MANAGE_POSTS staff (or PLATFORM_ADMIN) to write. GET isn't listed as authenticated
 * in SecurityConfig, so it falls through to the public "GET /api/churches/**" rule;
 * POST/DELETE aren't GET, so they already require authentication by default, and
 * PostService.requireCanManage enforces the actual permission on top of that.
 */
@RestController
@RequestMapping("/api/churches/{churchId}/posts")
public class PostController {

    private final PostService postService;
    private final UserRepository userRepository;

    public PostController(PostService postService, UserRepository userRepository) {
        this.postService = postService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<PostDto> list(
        @PathVariable Long churchId,
        @RequestParam(required = false) Long before,
        @RequestParam(required = false) Integer limit
    ) {
        return postService.list(churchId, before, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostDto create(@PathVariable Long churchId, @RequestBody CreatePostRequest request, Authentication authentication) {
        return postService.create(churchId, currentUser(authentication), request == null ? null : request.body());
    }

    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long churchId, @PathVariable Long postId, Authentication authentication) {
        postService.delete(churchId, postId, currentUser(authentication));
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
