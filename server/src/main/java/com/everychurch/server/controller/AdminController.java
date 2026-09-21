package com.everychurch.server.controller;

import com.everychurch.server.dto.CreateInviteRequest;
import com.everychurch.server.dto.VolunteerInviteResponse;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.VolunteerInviteService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Everything under /api/admin/** requires ROLE_PLATFORM_ADMIN — see SecurityConfig. */
@RestController
@RequestMapping("/api/admin/volunteer-invites")
public class AdminController {

    private final VolunteerInviteService inviteService;
    private final UserRepository userRepository;

    public AdminController(VolunteerInviteService inviteService, UserRepository userRepository) {
        this.inviteService = inviteService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<VolunteerInviteResponse> list() {
        return inviteService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VolunteerInviteResponse create(@RequestBody CreateInviteRequest request, Authentication authentication) {
        User admin = currentAdmin(authentication);
        return inviteService.generate(admin, request.expiresInHours());
    }

    @PatchMapping("/{id}/revoke")
    public VolunteerInviteResponse revoke(@PathVariable Long id) {
        return inviteService.revoke(id);
    }

    private User currentAdmin(Authentication authentication) {
        // The JWT filter sets the principal to the user's email (see JwtAuthenticationFilter).
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
