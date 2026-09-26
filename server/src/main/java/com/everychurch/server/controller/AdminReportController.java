package com.everychurch.server.controller;

import com.everychurch.server.dto.ReportDto;
import com.everychurch.server.dto.ResolveReportRequest;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** /api/admin/** requires ROLE_PLATFORM_ADMIN — see SecurityConfig. */
@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {

    private final ReportService reportService;
    private final UserRepository userRepository;

    public AdminReportController(ReportService reportService, UserRepository userRepository) {
        this.reportService = reportService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<ReportDto> list(@RequestParam(required = false) String status) {
        return reportService.list(status);
    }

    @PatchMapping("/{id}")
    public ReportDto resolve(@PathVariable Long id, @RequestBody ResolveReportRequest request, Authentication authentication) {
        return reportService.resolve(id, currentUser(authentication), request.status());
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
