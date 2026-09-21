package com.everychurch.server.controller;

import com.everychurch.server.dto.PresignedUploadRequest;
import com.everychurch.server.dto.PresignedUploadResponse;
import com.everychurch.server.dto.SermonDto;
import com.everychurch.server.dto.SermonRequest;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.SermonService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/churches/{churchId}/sermons")
public class SermonController {

    private final SermonService sermonService;
    private final UserRepository userRepository;

    public SermonController(SermonService sermonService, UserRepository userRepository) {
        this.sermonService = sermonService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<SermonDto> list(@PathVariable Long churchId) {
        return sermonService.list(churchId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SermonDto add(@PathVariable Long churchId, @RequestBody SermonRequest request, Authentication authentication) {
        return sermonService.add(churchId, currentUser(authentication), request.title(), request.date(), request.mediaUrl());
    }

    /** Client uploads the file directly to the returned uploadUrl, then POSTs publicUrl here via add(). */
    @PostMapping("/upload-url")
    public PresignedUploadResponse uploadUrl(@PathVariable Long churchId, @RequestBody PresignedUploadRequest request, Authentication authentication) {
        return sermonService.getUploadUrl(churchId, currentUser(authentication), request.contentType());
    }

    @PutMapping("/{sermonId}")
    public SermonDto update(
        @PathVariable Long churchId,
        @PathVariable Long sermonId,
        @RequestBody SermonRequest request,
        Authentication authentication
    ) {
        return sermonService.update(churchId, currentUser(authentication), sermonId, request.title(), request.date(), request.mediaUrl());
    }

    @DeleteMapping("/{sermonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long churchId, @PathVariable Long sermonId, Authentication authentication) {
        sermonService.remove(churchId, currentUser(authentication), sermonId);
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

    /** Uploads aren't configured (no S3_BUCKET_NAME) or AWS credentials are missing — a clear error, not a crash. */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleUploadUnavailable(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
