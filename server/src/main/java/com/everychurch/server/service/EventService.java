package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchRefDto;
import com.everychurch.server.dto.CoHostInvitationDto;
import com.everychurch.server.dto.EventDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.ChurchPermission;
import com.everychurch.server.model.Event;
import com.everychurch.server.model.EventCoHost;
import com.everychurch.server.model.EventCoHostStatus;
import org.springframework.transaction.annotation.Transactional;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchStaffRepository;
import com.everychurch.server.repository.EventCoHostRepository;
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
@Transactional
public class EventService {

    private final EventRepository eventRepository;
    private final EventCoHostRepository eventCoHostRepository;
    private final ChurchRepository churchRepository;
    private final ChurchStaffRepository churchStaffRepository;

    public EventService(
        EventRepository eventRepository,
        EventCoHostRepository eventCoHostRepository,
        ChurchRepository churchRepository,
        ChurchStaffRepository churchStaffRepository
    ) {
        this.eventRepository = eventRepository;
        this.eventCoHostRepository = eventCoHostRepository;
        this.churchRepository = churchRepository;
        this.churchStaffRepository = churchStaffRepository;
    }

    public List<EventDto> list(Long churchId) {
        List<Event> events = churchId == null ? eventRepository.findAll() : eventRepository.findByChurchId(churchId);
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
        setChurches(event, churches, requester);
        return toDto(eventRepository.save(event));
    }

    public EventDto update(Long id, User requester, String title, String description, Instant startsAt, Instant endsAt, Set<Long> churchIds) {
        Event event = eventRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Event not found: " + id));
        // must be able to manage at least one of the event's current churches, and at least one of the new ones
        requireCanManage(confirmedChurches(event), requester);
        Set<Church> churches = resolveChurches(churchIds);
        requireCanManage(churches, requester);

        event.setTitle(title);
        event.setDescription(description);
        event.setStartsAt(startsAt);
        event.setEndsAt(endsAt);
        setChurches(event, churches, requester);
        return toDto(eventRepository.save(event));
    }

    public void delete(Long id, User requester) {
        Event event = eventRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Event not found: " + id));
        requireCanManage(confirmedChurches(event), requester);
        eventRepository.delete(event);
    }

    /** Accept or decline a PENDING co-host invitation — callable only by someone with
     *  MANAGE_EVENTS on THAT SPECIFIC church (or a Platform Admin), never by the
     *  event's organizer on the invited church's behalf. This is the interactive half
     *  of the consent flow — create()/update() only ever mark a church PENDING, this
     *  is the only path that ever moves it to CONFIRMED or DECLINED. */
    public EventDto respondToCoHost(Long eventId, Long churchId, User requester, boolean accept) {
        EventCoHost coHost = eventCoHostRepository.findByEventIdAndChurchId(eventId, churchId)
            .orElseThrow(() -> new NoSuchElementException("No such co-host invitation"));

        if (!canManage(coHost.getChurch(), requester)) {
            throw new AccessDeniedException("You don't have permission to respond on behalf of this church");
        }
        if (coHost.getStatus() != EventCoHostStatus.PENDING) {
            throw new IllegalStateException("This invitation has already been responded to");
        }

        coHost.setStatus(accept ? EventCoHostStatus.CONFIRMED : EventCoHostStatus.DECLINED);
        coHost.setRespondedAt(Instant.now());
        eventCoHostRepository.save(coHost);

        return toDto(eventRepository.findById(eventId).orElseThrow());
    }

    /** Every outstanding "will your church co-host this event?" ask against a church
     *  the caller has MANAGE_EVENTS on — otherwise an invitation is invisible to the
     *  invited church unless someone happens to open that exact event page. */
    @Transactional(readOnly = true)
    public List<CoHostInvitationDto> myPendingCoHostInvitations(User user) {
        List<Long> managedChurchIds = churchStaffRepository.findByUserId(user.getId()).stream()
            .filter(staff -> staff.hasPermission(ChurchPermission.MANAGE_EVENTS))
            .map(staff -> staff.getChurch().getId())
            .toList();
        if (managedChurchIds.isEmpty()) return List.of();

        return eventCoHostRepository.findByChurchIdInAndStatus(managedChurchIds, EventCoHostStatus.PENDING).stream()
            .map(c -> new CoHostInvitationDto(
                c.getEvent().getId(), c.getEvent().getTitle(), c.getEvent().getStartsAt(),
                c.getChurch().getId(), c.getChurch().getName(),
                c.getCreatedAt()))
            .toList();
    }

    private Set<Church> confirmedChurches(Event event) {
        return event.getCoHosts().stream()
            .filter(host -> host.getStatus() == EventCoHostStatus.CONFIRMED)
            .map(EventCoHost::getChurch).collect(Collectors.toSet());
    }

    private boolean canManage(Church church, User requester) {
        return requester.getRole() == Role.PLATFORM_ADMIN ||
            churchStaffRepository.findByChurchIdAndUserId(church.getId(), requester.getId())
                .map(staff -> staff.hasPermission(ChurchPermission.MANAGE_EVENTS)).orElse(false);
    }

    private void setChurches(Event event, Set<Church> churches, User requester) {
        Set<Long> ids = churches.stream().map(Church::getId).collect(Collectors.toSet());
        event.getCoHosts().removeIf(host -> !ids.contains(host.getChurch().getId()));
        for (Church church : churches) {
            if (event.getCoHosts().stream().anyMatch(host -> host.getChurch().getId().equals(church.getId()))) continue;
            EventCoHost host = new EventCoHost();
            host.setEvent(event);
            host.setChurch(church);
            host.setInvitedBy(requester);
            host.setStatus(canManage(church, requester) ? EventCoHostStatus.CONFIRMED : EventCoHostStatus.PENDING);
            event.getCoHosts().add(host);
        }
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
        List<EventCoHost> coHosts = event.getCoHosts();

        // CONFIRMED + PENDING (not DECLINED) — what the edit form preselects, so
        // re-saving an event doesn't silently drop a still-outstanding invitation.
        Set<Long> churchIds = coHosts.stream()
            .filter(c -> c.getStatus() != EventCoHostStatus.DECLINED)
            .map(c -> c.getChurch().getId())
            .collect(Collectors.toSet());

        List<ChurchRefDto> churches = coHosts.stream()
            .filter(c -> c.getStatus() == EventCoHostStatus.CONFIRMED)
            .map(c -> new ChurchRefDto(c.getChurch().getId(), c.getChurch().getName()))
            .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
            .toList();

        List<ChurchRefDto> pendingChurches = coHosts.stream()
            .filter(c -> c.getStatus() == EventCoHostStatus.PENDING)
            .map(c -> new ChurchRefDto(c.getChurch().getId(), c.getChurch().getName()))
            .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
            .toList();

        return new EventDto(event.getId(), event.getTitle(), event.getDescription(), event.getStartsAt(), event.getEndsAt(), churchIds, churches, pendingChurches);
    }
}
