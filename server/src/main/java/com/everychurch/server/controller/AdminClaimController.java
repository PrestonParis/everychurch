package com.everychurch.server.controller;

import com.everychurch.server.dto.ClaimDto;
import com.everychurch.server.model.ClaimStatus;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ClaimService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** /api/admin/** requires ROLE_PLATFORM_ADMIN — see SecurityConfig. */
@RestController
@RequestMapping("/api/admin/claims")
public class AdminClaimController {

    private final ClaimService claimService;
    private final UserRepository userRepository;

    public AdminClaimController(ClaimService claimService, UserRepository userRepository) {
        this.claimService = claimService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<ClaimDto> list(@RequestParam(required = false) ClaimStatus status) {
        return claimService.list(status);
    }

    @PostMapping("/{id}/approve")
    public ClaimDto approve(@PathVariable Long id, Authentication authentication) {
        return claimService.approve(id, currentUser(authentication));
    }

    @PostMapping("/{id}/reject")
    public ClaimDto reject(@PathVariable Long id, Authentication authentication) {
        return claimService.reject(id, currentUser(authentication));
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
