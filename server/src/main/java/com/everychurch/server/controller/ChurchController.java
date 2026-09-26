package com.everychurch.server.controller;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.ParseUrlRequest;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.ChurchService;
import com.everychurch.server.service.ChurchViewService;
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
    private final ChurchViewService churchViewService;
    private final UserRepository userRepository;

    public ChurchController(
        ChurchService churchService,
        UrlParserService urlParserService,
        ChurchViewService churchViewService,
        UserRepository userRepository
    ) {
        this.churchService = churchService;
        this.urlParserService = urlParserService;
        this.churchViewService = churchViewService;
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
        // seed: client-generated per-browser-session value used to give a stable
        // pseudo-random default order (see ChurchRepository.search) instead of always
        // alphabetical. lat/lng: passed once the client already knows the user's
        // location (silently, via a prior geolocation grant — see church-list.component.ts),
        // to sort nearest-first instead. Both only affect ordering when query is blank.
        @RequestParam(defaultValue = "") String seed,
        @RequestParam(required = false) Double lat,
        @RequestParam(required = false) Double lng,
        @PageableDefault(size = 20, sort = "name") Pageable pageable
    ) {
        return churchService.search(query, denomination, state, city, seed, lat, lng, pageable);
    }

    // Typeahead suggestions for the city/state filter inputs — see ChurchRepository
    // .suggestCities/suggestStates. Public/no-auth like the other GETs here.
    @GetMapping("/cities")
    public List<String> cities(@RequestParam(defaultValue = "") String prefix, @RequestParam(defaultValue = "") String state) {
        return churchService.suggestCities(prefix, state);
    }

    @GetMapping("/states")
    public List<String> states(@RequestParam(defaultValue = "") String prefix) {
        return churchService.suggestStates(prefix);
    }

    @GetMapping("/{id}")
    public ChurchDto get(@PathVariable Long id) {
        return churchService.findById(id);
    }

    // Fire-and-forget from ChurchProfileComponent on every profile load, only when the
    // viewer is logged in (no User row to attach an anonymous view to, and no
    // Favorites page for them to see "recently viewed" on anyway). Not covered by the
    // public GET rule below — POST already requires authentication by default.
    @PostMapping("/{id}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordView(@PathVariable Long id, Authentication authentication) {
        churchViewService.recordView(id, currentUser(authentication));
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

    // Non-blocking hint, not validation — see ChurchService.findPossibleDuplicates. Left
    // public/no-auth like the other GETs here; it returns nothing more than search()
    // already would (public church names/cities), just filtered by similarity.
    @GetMapping("/duplicate-check")
    public List<ChurchDto> duplicateCheck(
        @RequestParam String name,
        @RequestParam(defaultValue = "") String city,
        @RequestParam(defaultValue = "") String zipCode,
        @RequestParam(required = false) Long excludeId
    ) {
        return churchService.findPossibleDuplicates(name, city, zipCode, excludeId);
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
