package com.everychurch.server.service;

import com.everychurch.server.dto.ScraperRunDto;
import com.everychurch.server.dto.TriggerScraperRunResponse;
import com.everychurch.server.model.ScraperRun;
import com.everychurch.server.model.ScraperRunStatus;
import com.everychurch.server.repository.ScraperRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ScraperRunService {

    /**
     * Same 50 state codes as apps/scraper/scraper/regions.py's STATE_BOUNDING_BOXES
     * keys — kept in sync manually since the two sides don't share code across the
     * Java/Python boundary. Used as the default candidate list for both the
     * autonomous lambda_handler (via GET .../next-region) and the admin-triggered
     * run below when no region is specified.
     */
    public static final List<String> ALL_REGIONS = List.of(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
        "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
        "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
        "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
        "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY"
    );

    private final ScraperRunRepository scraperRunRepository;
    private final ScraperInvoker scraperInvoker;

    public ScraperRunService(ScraperRunRepository scraperRunRepository, ScraperInvoker scraperInvoker) {
        this.scraperRunRepository = scraperRunRepository;
        this.scraperInvoker = scraperInvoker;
    }

    public ScraperRunDto start(String region) {
        ScraperRun run = new ScraperRun();
        run.setRegion(region);
        run.setStatus(ScraperRunStatus.RUNNING);
        return toDto(scraperRunRepository.save(run));
    }

    public ScraperRunDto complete(Long id, ScraperRunStatus status, Integer discovered, Integer newCount, Integer accepted) {
        ScraperRun run = scraperRunRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("No such scraper run: " + id));

        run.setStatus(status);
        run.setDiscovered(discovered);
        run.setNewCount(newCount);
        run.setAccepted(accepted);
        run.setCompletedAt(Instant.now());
        return toDto(scraperRunRepository.save(run));
    }

    public List<ScraperRunDto> list() {
        return scraperRunRepository.findAllByOrderByStartedAtDesc().stream().map(this::toDto).toList();
    }

    /**
     * Whichever candidate has the oldest-or-no COMPLETED run — giving automatic
     * round-robin coverage over all candidates with no external scheduler state.
     * Only COMPLETED runs count toward recency; a candidate whose only history is
     * RUNNING/FAILED is treated the same as one that's never run at all.
     */
    public String nextRegion(List<String> candidates) {
        String best = null;
        Instant bestCompletedAt = null;

        for (String candidate : candidates) {
            Optional<ScraperRun> lastCompleted =
                scraperRunRepository.findFirstByRegionAndStatusOrderByCompletedAtDesc(candidate, ScraperRunStatus.COMPLETED);

            if (lastCompleted.isEmpty()) {
                return candidate; // never completed a run — top priority, first such candidate wins
            }

            Instant completedAt = lastCompleted.get().getCompletedAt();
            if (best == null || completedAt.isBefore(bestCompletedAt)) {
                best = candidate;
                bestCompletedAt = completedAt;
            }
        }

        return best;
    }

    /**
     * Admin-triggered run (Prompt 6): picks a region if none was given, records a
     * RUNNING ScraperRun, and hands off to the configured ScraperInvoker — today
     * that's just a log line + "run it locally" message, since no Lambda is
     * deployed yet.
     */
    public TriggerScraperRunResponse trigger(String requestedRegion) {
        String region = (requestedRegion == null || requestedRegion.isBlank())
            ? nextRegion(ALL_REGIONS)
            : requestedRegion;

        ScraperRunDto run = start(region);
        String message = scraperInvoker.trigger(region, run.id());
        return new TriggerScraperRunResponse(run, message);
    }

    private ScraperRunDto toDto(ScraperRun run) {
        return new ScraperRunDto(
            run.getId(), run.getRegion(), run.getStartedAt(), run.getCompletedAt(),
            run.getDiscovered(), run.getNewCount(), run.getAccepted(), run.getStatus()
        );
    }
}
