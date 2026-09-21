package com.everychurch.server.controller;

import com.everychurch.server.dto.AddStaffRequest;
import com.everychurch.server.dto.ChurchStaffDto;
import com.everychurch.server.dto.UpdateStaffPermissionsRequest;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ChurchStaffService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/churches/{churchId}/staff")
public class ChurchStaffController {

    private final ChurchStaffService churchStaffService;
    private final UserRepository userRepository;

    public ChurchStaffController(ChurchStaffService churchStaffService, UserRepository userRepository) {
        this.churchStaffService = churchStaffService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<ChurchStaffDto> list(@PathVariable Long churchId, Authentication authentication) {
        return churchStaffService.listStaff(churchId, currentUser(authentication));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChurchStaffDto add(@PathVariable Long churchId, @RequestBody AddStaffRequest request, Authentication authentication) {
        return churchStaffService.addStaff(churchId, currentUser(authentication), request.email(), request.permissions());
    }

    @PatchMapping("/{staffId}")
    public ChurchStaffDto updatePermissions(
        @PathVariable Long churchId,
        @PathVariable Long staffId,
        @RequestBody UpdateStaffPermissionsRequest request,
        Authentication authentication
    ) {
        return churchStaffService.updatePermissions(churchId, currentUser(authentication), staffId, request.permissions());
    }

    @DeleteMapping("/{staffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long churchId, @PathVariable Long staffId, Authentication authentication) {
        churchStaffService.removeStaff(churchId, currentUser(authentication), staffId);
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
