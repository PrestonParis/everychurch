package com.everychurch.server.controller;

import com.everychurch.server.dto.EventDto;
import com.everychurch.server.dto.EventRequest;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.EventService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;
    private final UserRepository userRepository;

    public EventController(EventService eventService, UserRepository userRepository) {
        this.eventService = eventService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<EventDto> list(@RequestParam(required = false) Long churchId) {
        return eventService.list(churchId);
    }

    @GetMapping("/{id}")
    public EventDto get(@PathVariable Long id) {
        return eventService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventDto create(@RequestBody EventRequest request, Authentication authentication) {
        return eventService.create(
            currentUser(authentication), request.title(), request.description(), request.startsAt(), request.endsAt(), request.churchIds());
    }

    @PutMapping("/{id}")
    public EventDto update(@PathVariable Long id, @RequestBody EventRequest request, Authentication authentication) {
        return eventService.update(
            id, currentUser(authentication), request.title(), request.description(), request.startsAt(), request.endsAt(), request.churchIds());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        eventService.delete(id, currentUser(authentication));
    }

    // Co-host consent (EventCoHost) — only someone with MANAGE_EVENTS on churchId
    // itself may accept/decline on its behalf, enforced in EventService.respondToCoHost.
    @PostMapping("/{id}/co-hosts/{churchId}/accept")
    public EventDto acceptCoHost(@PathVariable Long id, @PathVariable Long churchId, Authentication authentication) {
        return eventService.respondToCoHost(id, churchId, currentUser(authentication), true);
    }

    @PostMapping("/{id}/co-hosts/{churchId}/decline")
    public EventDto declineCoHost(@PathVariable Long id, @PathVariable Long churchId, Authentication authentication) {
        return eventService.respondToCoHost(id, churchId, currentUser(authentication), false);
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

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleConflict(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
