package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.ServiceTimeDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.ChurchPermission;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.ServiceTime;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchStaffRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@Transactional
public class ChurchService {

    private final ChurchRepository repo;
    private final ChurchStaffRepository churchStaffRepository;
    private final GeocodingService geocodingService;

    public ChurchService(ChurchRepository repo, ChurchStaffRepository churchStaffRepository, GeocodingService geocodingService) {
        this.repo = repo;
        this.churchStaffRepository = churchStaffRepository;
        this.geocodingService = geocodingService;
    }

    // Denomination/state/city arrive as "" rather than null from the controller
    // (see ChurchController.list) so a single blank-check works for every filter in
    // the native query without null-vs-empty inconsistency across drivers.
    //
    // Only page/size are taken from the incoming Pageable, deliberately dropping any
    // Sort it carries (the controller's @PageableDefault(sort = "name") included):
    // repo.search's native SQL already fully owns ordering (relevance when searching,
    // name otherwise), and Spring Data's native-query support for splicing an
    // additional ORDER BY from Pageable.getSort() on top of a query that already has
    // one is exactly the kind of fragile, driver-version-dependent behavior worth
    // avoiding rather than relying on.
    @Transactional(readOnly = true)
    public Page<ChurchDto> search(String query, String denomination, String state, String city, Pageable pageable) {
        Pageable pageOnly = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return repo.search(query, denomination, state, city, pageOnly).map(this::toSummaryDto);
    }

    @Transactional(readOnly = true)
    public ChurchDto findById(Long id) {
        return repo.findById(id)
            .map(this::toDto)
            .orElseThrow(() -> new NoSuchElementException("Church not found: " + id));
    }

    public ChurchDto create(ChurchDto dto) {
        Church church = new Church();
        applyDto(church, dto);
        Church saved = repo.save(church);
        syncGeoPoint(saved);
        return toDto(saved);
    }

    public ChurchDto update(Long id, ChurchDto dto, User requester) {
        Church church = repo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Church not found: " + id));

        // Once claimed, only the owner/staff with EDIT_PROFILE (or a Platform Admin) may edit —
        // an unclaimed church has no owner yet, so anyone authenticated can still set it up.
        if (church.isClaimed()) {
            requirePermission(id, requester, ChurchPermission.EDIT_PROFILE);
        }

        applyDto(church, dto);
        Church saved = repo.save(church);
        syncGeoPoint(saved);
        return toDto(saved);
    }

    // geo_point isn't JPA-mapped (Hibernate doesn't natively speak PostGIS types), so
    // it never travels with repo.save() above — this keeps it in permanent sync with
    // latitude/longitude right after, since both are derived from the same source of
    // truth and must never drift apart. A church with no coordinates yet (geocoding
    // unconfigured/failed) simply gets no geo_point, same as it gets no lat/lng.
    private void syncGeoPoint(Church c) {
        if (c.getLatitude() != null && c.getLongitude() != null) {
            repo.updateGeoPoint(c.getId(), c.getLatitude(), c.getLongitude());
        }
    }

    public void delete(Long id, User requester) {
        Church church = repo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Church not found: " + id));

        // Deleting is more destructive than editing — once claimed, only the owner
        // (not just any staff member) or a Platform Admin may do it.
        if (church.isClaimed()) {
            requireOwner(id, requester);
        }

        repo.deleteById(id);
    }

    private void requirePermission(Long churchId, User requester, ChurchPermission permission) {
        if (requester.getRole() == Role.PLATFORM_ADMIN) return;

        boolean allowed = churchStaffRepository.findByChurchIdAndUserId(churchId, requester.getId())
            .map(staff -> staff.hasPermission(permission))
            .orElse(false);

        if (!allowed) {
            throw new AccessDeniedException("You don't have permission to edit this church");
        }
    }

    private void requireOwner(Long churchId, User requester) {
        if (requester.getRole() == Role.PLATFORM_ADMIN) return;

        boolean isOwner = churchStaffRepository.findByChurchIdAndUserId(churchId, requester.getId())
            .map(staff -> staff.isOwner())
            .orElse(false);

        if (!isOwner) {
            throw new AccessDeniedException("Only the church owner can delete this church");
        }
    }

    private void applyDto(Church c, ChurchDto dto) {
        c.setName(dto.name());
        c.setDenomination(dto.denomination());
        c.setAddress(dto.address());
        c.setCity(dto.city());
        c.setState(dto.state());
        c.setZipCode(dto.zipCode());
        c.setPhone(dto.phone());
        c.setEmail(dto.email());
        c.setWebsite(dto.website());
        c.setDescription(dto.description());
        c.setImageUrl(dto.imageUrl());
        c.setPastor(dto.pastor());
        c.setFacebook(dto.facebook());
        c.setInstagram(dto.instagram());
        c.setYoutube(dto.youtube());
        // claimed is intentionally NOT settable from the request body — it's only ever
        // set by ClaimService.approve(), so a PUT here can't bypass the claim workflow.

        // Client-supplied coordinates win when present (e.g. the scraper already has
        // lat/lng from the Places API) — otherwise geocode the address server-side.
        // A failed/unconfigured geocode leaves any existing coordinates untouched
        // rather than wiping them.
        if (dto.latitude() != null && dto.longitude() != null) {
            c.setLatitude(dto.latitude());
            c.setLongitude(dto.longitude());
        } else {
            geocodingService.geocode(fullAddress(c)).ifPresent(coords -> {
                c.setLatitude(coords[0]);
                c.setLongitude(coords[1]);
            });
        }

        c.getServiceTimes().clear();
        if (dto.serviceTimes() != null) {
            for (ServiceTimeDto st : dto.serviceTimes()) {
                ServiceTime s = new ServiceTime();
                s.setDayOfWeek(st.dayOfWeek());
                s.setTime(st.time());
                s.setType(st.type());
                s.setLanguage(st.language());
                s.setChurch(c);
                c.getServiceTimes().add(s);
            }
        }
    }

    private ChurchDto toDto(Church c) {
        List<ServiceTimeDto> times = c.getServiceTimes().stream()
            .map(s -> new ServiceTimeDto(s.getId(), s.getDayOfWeek(), s.getTime(), s.getType(), s.getLanguage()))
            .toList();
        return new ChurchDto(
            c.getId(), c.getName(), c.getDenomination(),
            c.getAddress(), c.getCity(), c.getState(), c.getZipCode(),
            c.getPhone(), c.getEmail(), c.getWebsite(),
            c.getDescription(), c.getImageUrl(), c.getPastor(),
            c.getFacebook(), c.getInstagram(), c.getYoutube(),
            c.isClaimed(), c.getLatitude(), c.getLongitude(), times
        );
    }

    // Used for list/search/nearest results: never touches the (now-LAZY)
    // serviceTimes association, so no N+1 select per row — see Church.serviceTimes.
    private ChurchDto toSummaryDto(Church c) {
        return new ChurchDto(
            c.getId(), c.getName(), c.getDenomination(),
            c.getAddress(), c.getCity(), c.getState(), c.getZipCode(),
            c.getPhone(), c.getEmail(), c.getWebsite(),
            c.getDescription(), c.getImageUrl(), c.getPastor(),
            c.getFacebook(), c.getInstagram(), c.getYoutube(),
            c.isClaimed(), c.getLatitude(), c.getLongitude(), List.of()
        );
    }

    private String fullAddress(Church c) {
        return java.util.stream.Stream.of(c.getAddress(), c.getCity(), c.getState(), c.getZipCode())
            .filter(s -> s != null && !s.isBlank())
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    }

    /** Nearest churches to (lat, lng) regardless of distance, closest first. */
    @Transactional(readOnly = true)
    public List<ChurchDto> findNearest(double lat, double lng, int limit) {
        return findNearest(lat, lng, null, limit);
    }

    /**
     * Nearest churches to (lat, lng), closest first, via PostGIS KNN (ChurchRepository
     * .findNearest — the `<->` operator against the GiST index on geo_point). A null
     * radiusMeters keeps today's "N closest regardless of distance" behavior; set, it
     * also excludes anything farther than that (so fewer than `limit` results is
     * possible). Churches with no coordinates yet never have a geo_point and so never
     * appear here — same exclusion the old Haversine version had, just enforced by the
     * WHERE clause instead of a null-check in application code.
     */
    @Transactional(readOnly = true)
    public List<ChurchDto> findNearest(double lat, double lng, Double radiusMeters, int limit) {
        return repo.findNearest(lat, lng, radiusMeters, limit).stream()
            .map(this::toSummaryDto)
            .toList();
    }
}
