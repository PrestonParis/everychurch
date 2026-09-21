package com.everychurch.server.controller;

import com.everychurch.server.dto.ChurchImageDto;
import com.everychurch.server.dto.ChurchImageRequest;
import com.everychurch.server.dto.PresignedUploadRequest;
import com.everychurch.server.dto.PresignedUploadResponse;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ChurchImageService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/churches/{churchId}/images")
public class ChurchImageController {

    private final ChurchImageService churchImageService;
    private final UserRepository userRepository;

    public ChurchImageController(ChurchImageService churchImageService, UserRepository userRepository) {
        this.churchImageService = churchImageService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<ChurchImageDto> list(@PathVariable Long churchId) {
        return churchImageService.list(churchId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChurchImageDto add(@PathVariable Long churchId, @RequestBody ChurchImageRequest request, Authentication authentication) {
        return churchImageService.add(churchId, currentUser(authentication), request.url(), request.sortOrder());
    }

    /** Client uploads the file directly to the returned uploadUrl, then POSTs publicUrl here via add(). */
    @PostMapping("/upload-url")
    public PresignedUploadResponse uploadUrl(@PathVariable Long churchId, @RequestBody PresignedUploadRequest request, Authentication authentication) {
        return churchImageService.getUploadUrl(churchId, currentUser(authentication), request.contentType());
    }

    @PutMapping("/{imageId}")
    public ChurchImageDto update(
        @PathVariable Long churchId,
        @PathVariable Long imageId,
        @RequestBody ChurchImageRequest request,
        Authentication authentication
    ) {
        return churchImageService.update(churchId, currentUser(authentication), imageId, request.url(), request.sortOrder());
    }

    @DeleteMapping("/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long churchId, @PathVariable Long imageId, Authentication authentication) {
        churchImageService.remove(churchId, currentUser(authentication), imageId);
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
