package com.everychurch.server.controller;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.ParseUrlRequest;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ChurchService;
import com.everychurch.server.service.UrlParserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/churches")
public class ChurchController {

    private final ChurchService churchService;
    private final UrlParserService urlParserService;
    private final UserRepository userRepository;

    public ChurchController(ChurchService churchService, UrlParserService urlParserService, UserRepository userRepository) {
        this.churchService = churchService;
        this.urlParserService = urlParserService;
        this.userRepository = userRepository;
    }

    // Breaking response-shape change from the old bare array: a Spring Page renders as
    // {content, totalElements, totalPages, number, size, ...} — the Angular client
    // (church.service.ts) is rebuilt against this shape in the same pass.
    @GetMapping
    public Page<ChurchDto> list(
        @RequestParam(defaultValue = "") String query,
        @RequestParam(defaultValue = "") String denomination,
        @RequestParam(defaultValue = "") String state,
        @RequestParam(defaultValue = "") String city,
        @PageableDefault(size = 20, sort = "name") Pageable pageable
    ) {
        return churchService.search(query, denomination, state, city, pageable);
    }

    @GetMapping("/{id}")
    public ChurchDto get(@PathVariable Long id) {
        return churchService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChurchDto create(@RequestBody ChurchDto dto) {
        return churchService.create(dto);
    }

    @PutMapping("/{id}")
    public ChurchDto update(@PathVariable Long id, @RequestBody ChurchDto dto, Authentication authentication) {
        return churchService.update(id, dto, currentUser(authentication));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        churchService.delete(id, currentUser(authentication));
    }

    private User currentUser(Authentication authentication) {
        String email = (String) authentication.getPrincipal();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new NoSuchElementException("Authenticated user no longer exists"));
    }

    @PostMapping("/parse-url")
    public ChurchDto parseUrl(@RequestBody ParseUrlRequest request) {
        return urlParserService.parse(request.url());
    }

    @GetMapping("/nearest")
    public List<ChurchDto> nearest(
        @RequestParam double lat,
        @RequestParam double lng,
        @RequestParam(required = false) Double radiusMeters,
        @RequestParam(defaultValue = "5") int limit
    ) {
        return churchService.findNearest(lat, lng, radiusMeters, limit);
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
