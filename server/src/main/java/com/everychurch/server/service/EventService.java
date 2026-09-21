package com.everychurch.server.service;

import com.everychurch.server.dto.EventDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.ChurchPermission;
import com.everychurch.server.model.Event;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchStaffRepository;
import com.everychurch.server.repository.EventRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final ChurchRepository churchRepository;
    private final ChurchStaffRepository churchStaffRepository;

    public EventService(
        EventRepository eventRepository,
        ChurchRepository churchRepository,
        ChurchStaffRepository churchStaffRepository
    ) {
        this.eventRepository = eventRepository;
        this.churchRepository = churchRepository;
        this.churchStaffRepository = churchStaffRepository;
    }

    public List<EventDto> list(Long churchId) {
        List<Event> events = churchId == null ? eventRepository.findAll() : eventRepository.findByChurchesId(churchId);
        return events.stream().map(this::toDto).toList();
    }

    public EventDto get(Long id) {
        return eventRepository.findById(id)
            .map(this::toDto)
            .orElseThrow(() -> new NoSuchElementException("Event not found: " + id));
    }

    public EventDto create(User requester, String title, String description, Instant startsAt, Instant endsAt, Set<Long> churchIds) {
        Set<Church> churches = resolveChurches(churchIds);
        requireCanManage(churches, requester);

        Event event = new Event();
        event.setTitle(title);
        event.setDescription(description);
        event.setStartsAt(startsAt);
        event.setEndsAt(endsAt);
        event.setChurches(churches);
        return toDto(eventRepository.save(event));
    }

    public EventDto update(Long id, User requester, String title, String description, Instant startsAt, Instant endsAt, Set<Long> churchIds) {
        Event event = eventRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Event not found: " + id));
        // must be able to manage at least one of the event's current churches, and at least one of the new ones
        requireCanManage(event.getChurches(), requester);
        Set<Church> churches = resolveChurches(churchIds);
        requireCanManage(churches, requester);

        event.setTitle(title);
        event.setDescription(description);
        event.setStartsAt(startsAt);
        event.setEndsAt(endsAt);
        event.setChurches(churches);
        return toDto(eventRepository.save(event));
    }

    public void delete(Long id, User requester) {
        Event event = eventRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Event not found: " + id));
        requireCanManage(event.getChurches(), requester);
        eventRepository.delete(event);
    }

    private Set<Church> resolveChurches(Set<Long> churchIds) {
        if (churchIds == null || churchIds.isEmpty()) {
            throw new IllegalArgumentException("Event must have at least one church");
        }
        Set<Church> churches = new HashSet<>();
        for (Long id : churchIds) {
            churches.add(churchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Church not found: " + id)));
        }
        return churches;
    }

    /** Requires MANAGE_EVENTS on at least one of the given churches. */
    private void requireCanManage(Set<Church> churches, User requester) {
        if (requester.getRole() == Role.PLATFORM_ADMIN) return;

        boolean canManage = churches.stream().anyMatch(church ->
            churchStaffRepository.findByChurchIdAndUserId(church.getId(), requester.getId())
                .map(staff -> staff.hasPermission(ChurchPermission.MANAGE_EVENTS))
                .orElse(false));

        if (!canManage) {
            throw new AccessDeniedException("You don't have permission to manage events for this church");
        }
    }

    private EventDto toDto(Event event) {
        Set<Long> churchIds = event.getChurches().stream().map(Church::getId).collect(Collectors.toSet());
        return new EventDto(event.getId(), event.getTitle(), event.getDescription(), event.getStartsAt(), event.getEndsAt(), churchIds);
    }
}
