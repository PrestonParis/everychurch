package com.everychurch.server.controller;

import com.everychurch.server.dto.ChurchSubmissionDto;
import com.everychurch.server.model.ChurchSubmissionStatus;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ChurchSubmissionService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** /api/admin/** requires ROLE_PLATFORM_ADMIN — see SecurityConfig. */
@RestController
@RequestMapping("/api/admin/submissions")
public class AdminChurchSubmissionController {

    private final ChurchSubmissionService submissionService;
    private final UserRepository userRepository;

    public AdminChurchSubmissionController(ChurchSubmissionService submissionService, UserRepository userRepository) {
        this.submissionService = submissionService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<ChurchSubmissionDto> list(@RequestParam(required = false) ChurchSubmissionStatus status) {
        return submissionService.list(status);
    }

    @PostMapping("/{id}/approve")
    public ChurchSubmissionDto approve(@PathVariable Long id, Authentication authentication) {
        return submissionService.approve(id, currentUser(authentication));
    }

    @PostMapping("/{id}/reject")
    public ChurchSubmissionDto reject(@PathVariable Long id, Authentication authentication) {
        return submissionService.reject(id, currentUser(authentication));
    }

    private User currentUser(Authentication authentication) {
        String email = (String) authentication.getPrincipal();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new NoSuchElementException("Authenticated user no longer exists"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(NoSuchElementException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    public Map<String, String> handleConflict(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
