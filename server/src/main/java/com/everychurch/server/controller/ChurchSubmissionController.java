package com.everychurch.server.controller;

import com.everychurch.server.dto.ChurchSubmissionDto;
import com.everychurch.server.dto.ChurchSubmissionRequest;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ChurchSubmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

/** Volunteer-facing submission endpoints. Review/approval lives in AdminChurchSubmissionController. */
@RestController
public class ChurchSubmissionController {

    private final ChurchSubmissionService submissionService;
    private final UserRepository userRepository;

    public ChurchSubmissionController(ChurchSubmissionService submissionService, UserRepository userRepository) {
        this.submissionService = submissionService;
        this.userRepository = userRepository;
    }

    @PostMapping("/api/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public ChurchSubmissionDto proposeNewChurch(@RequestBody ChurchSubmissionRequest request, Authentication authentication) {
        return submissionService.submitNewChurch(request.proposedChanges(), currentUser(authentication));
    }

    @PostMapping("/api/churches/{churchId}/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public ChurchSubmissionDto proposeEdit(
        @PathVariable Long churchId,
        @RequestBody ChurchSubmissionRequest request,
        Authentication authentication
    ) {
        return submissionService.submitEdit(churchId, request.proposedChanges(), currentUser(authentication));
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
}
