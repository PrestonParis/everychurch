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

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

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
    // distance/seeded-shuffle otherwise), and Spring Data's native-query support for
    // splicing an additional ORDER BY from Pageable.getSort() on top of a query that
    // already has one is exactly the kind of fragile, driver-version-dependent behavior
    // worth avoiding rather than relying on.
    //
    // seed/lat/lng only affect ordering when query is blank (see repo.search's ORDER BY):
    // lat+lng (when the client already knows the user's location) sort nearest-first;
    // otherwise seed gives a stable-per-browser-session shuffle instead of always
    // alphabetical. Harmless to pass through unconditionally when a query IS present —
    // the native query ignores them in that branch.
    @Transactional(readOnly = true)
    public Page<ChurchDto> search(
        String query, String denomination, String state, String city, String seed, Double lat, Double lng, Pageable pageable
    ) {
        Pageable pageOnly = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return repo.search(query, denomination, state, city, seed, lat, lng, pageOnly).map(this::toSummaryDto);
    }

    @Transactional(readOnly = true)
    public List<String> suggestCities(String prefix, String state) {
        return repo.suggestCities(prefix, state);
    }

    @Transactional(readOnly = true)
    public List<String> suggestStates(String prefix) {
        return repo.suggestStates(prefix);
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

        // Once claimed, only the owner/staff with EDIT_PROFILE (or a Platform Admin) may edit.
        // An unclaimed church has no owner yet — that USED to mean any authenticated user
        // could edit it, but per project-owner feedback (docs/PROMPTS.md) that's now
        // Platform Admin only; a per-church "appointed admin" only exists once claimed
        // (ChurchStaff), so it can't apply here. Regular contributors still have a path in:
        // the ChurchSubmission review queue (volunteers propose, an admin approves).
        if (church.isClaimed()) {
            requirePermission(id, requester, ChurchPermission.EDIT_PROFILE);
        } else {
            requirePlatformAdmin(requester, "Only a Platform Admin can edit an unclaimed church");
        }

        applyDto(church, dto);
        Church saved = repo.save(church);
        syncGeoPoint(saved);
        return toDto(saved);
    }

    /**
     * Backfill from a trusted bulk source (BulkImportController's /fill, fed by
     * apps/scraper's osm_fill.py): copies each non-blank field of {@code dto} onto the
     * church ONLY where the church's own value is blank — never overwrites, so a re-run
     * or a second source can't clobber what's already there. Service times are filled
     * only when the church has none at all (merging two schedules would invent one
     * nobody published). A claimed church is skipped entirely: its owner manages it,
     * and an import silently editing a claimed profile is exactly what claiming is
     * meant to prevent. Name, state and coordinates are never touched — the caller
     * matched on them. Returns false when nothing changed (unknown id, claimed, or no
     * blank the dto could fill).
     */
    public boolean fillBlanks(ChurchDto dto) {
        if (dto.id() == null) return false;
        Church c = repo.findById(dto.id()).orElse(null);
        if (c == null || c.isClaimed()) return false;

        boolean changed = false;
        changed |= fill(c.getDenomination(), dto.denomination(), c::setDenomination);
        changed |= fill(c.getAddress(), dto.address(), c::setAddress);
        changed |= fill(c.getCity(), dto.city(), c::setCity);
        changed |= fill(c.getZipCode(), dto.zipCode(), c::setZipCode);
        changed |= fill(c.getPhone(), dto.phone(), c::setPhone);
        changed |= fill(c.getEmail(), dto.email(), c::setEmail);
        changed |= fill(c.getWebsite(), dto.website(), c::setWebsite);
        changed |= fill(c.getDescription(), dto.description(), c::setDescription);
        changed |= fill(c.getImageUrl(), dto.imageUrl(), c::setImageUrl);
        changed |= fill(c.getPastor(), dto.pastor(), c::setPastor);
        changed |= fill(c.getFacebook(), dto.facebook(), c::setFacebook);
        changed |= fill(c.getInstagram(), dto.instagram(), c::setInstagram);
        changed |= fill(c.getYoutube(), dto.youtube(), c::setYoutube);

        if (c.getServiceTimes().isEmpty() && dto.serviceTimes() != null && !dto.serviceTimes().isEmpty()) {
            for (ServiceTimeDto st : dto.serviceTimes()) {
                ServiceTime s = new ServiceTime();
                s.setDayOfWeek(st.dayOfWeek());
                s.setTime(st.time());
                s.setType(st.type());
                s.setLanguage(st.language());
                s.setChurch(c);
                c.getServiceTimes().add(s);
            }
            c.setServiceSummary(buildServiceSummary(dto.serviceTimes()));
            changed = true;
        }

        if (changed) repo.save(c);
        return changed;
    }

    private static boolean fill(String current, String proposed, java.util.function.Consumer<String> setter) {
        if ((current != null && !current.isBlank()) || proposed == null || proposed.isBlank()) return false;
        setter.accept(proposed.trim());
        return true;
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
        // (not just any staff member) or a Platform Admin may do it. An unclaimed
        // church has no owner to check, but it's NOT therefore open season: this used
        // to fall through with no check at all, so any authenticated user could delete
        // any of the ~42k unclaimed bulk-imported churches. Same rule as update()'s
        // unclaimed branch — Platform Admin only.
        if (church.isClaimed()) {
            requireOwner(id, requester);
        } else {
            requirePlatformAdmin(requester, "Only a Platform Admin can delete an unclaimed church");
        }

        repo.deleteById(id);
    }

    private void requirePlatformAdmin(User requester, String message) {
        if (requester.getRole() != Role.PLATFORM_ADMIN) {
            throw new AccessDeniedException(message);
        }
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
        c.setServiceSummary(buildServiceSummary(dto.serviceTimes()));
    }

    private static final List<String> DAY_ORDER =
        List.of("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a");

    /**
     * A short, cheap-to-read summary like "Sundays 9:00 AM & 11:00 AM +1 more" — every time
     * on the earliest weekday (Sunday first), plus a count of how many other days also have
     * one. Recomputed here (write time) rather than read time so the list endpoint can
     * return it without ever touching the LAZY serviceTimes collection.
     */
    private String buildServiceSummary(List<ServiceTimeDto> times) {
        if (times == null || times.isEmpty()) return null;

        ServiceTimeDto first = times.stream()
            .filter(t -> t.dayOfWeek() != null && t.time() != null)
            .min(Comparator.comparingInt((ServiceTimeDto t) -> {
                int i = DAY_ORDER.indexOf(t.dayOfWeek());
                return i < 0 ? DAY_ORDER.size() : i;
            }).thenComparing(ServiceTimeDto::time))
            .orElse(null);
        if (first == null) return null;

        List<String> sameDay = times.stream()
            .filter(t -> first.dayOfWeek().equals(t.dayOfWeek()) && t.time() != null)
            .map(ServiceTimeDto::time)
            .sorted()
            .map(this::formatTime)
            .toList();

        long otherDays = times.stream()
            .map(ServiceTimeDto::dayOfWeek).filter(Objects::nonNull).distinct().count() - 1;

        String summary = first.dayOfWeek() + "s " + String.join(" & ", sameDay);
        return otherDays > 0 ? summary + " +" + otherDays + " more" : summary;
    }

    private String formatTime(String hhmm) {
        try {
            return java.time.LocalTime.parse(hhmm).format(TIME_FORMAT);
        } catch (Exception e) {
            return hhmm; // an unparsable value (shouldn't happen via the form, but data can be messy) — show it as-is rather than dropping it
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
            c.isClaimed(), c.getLatitude(), c.getLongitude(), c.getServiceSummary(), times
        );
    }

    /** Public wrapper around {@link #toSummaryDto} for other services that already have
     *  a loaded {@link Church} in hand and just need the same card-view shape the list/
     *  search endpoints use — FavoriteService.listMyFavorites, at the moment. */
    public ChurchDto summaryOf(Church c) {
        return toSummaryDto(c);
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
            c.isClaimed(), c.getLatitude(), c.getLongitude(), c.getServiceSummary(), List.of()
        );
    }

    /**
     * Near-name-matches in the same city or zip, excluding the church itself when checking
     * an existing one. A hint for a human (admin creating directly, or reviewing a
     * ChurchSubmission), not a hard block — a false positive shouldn't stop a real church
     * from being listed, so this never blocks create/update, only surfaces candidates.
     */
    @Transactional(readOnly = true)
    public List<ChurchDto> findPossibleDuplicates(String name, String city, String zipCode, Long excludeId) {
        if (name == null || name.isBlank()) return List.of();
        return repo.findPossibleDuplicates(name.trim(), city == null ? "" : city, zipCode == null ? "" : zipCode, excludeId)
            .stream().map(this::toSummaryDto).toList();
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
