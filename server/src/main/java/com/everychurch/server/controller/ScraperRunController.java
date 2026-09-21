package com.everychurch.server.controller;

import com.everychurch.server.dto.CompleteScraperRunRequest;
import com.everychurch.server.dto.NextRegionResponse;
import com.everychurch.server.dto.ScraperRunDto;
import com.everychurch.server.dto.StartScraperRunRequest;
import com.everychurch.server.service.ScraperRunService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Scraper run tracking (docs/SCRAPER.md). Authenticated via the X-Scraper-Api-Key
 * header (ScraperApiKeyFilter + SecurityConfig's hasRole("SCRAPER") rule on
 * /api/scraper/**), same as the ingestion endpoint.
 */
@RestController
@RequestMapping("/api/scraper/runs")
public class ScraperRunController {

    private final ScraperRunService scraperRunService;

    public ScraperRunController(ScraperRunService scraperRunService) {
        this.scraperRunService = scraperRunService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScraperRunDto start(@RequestBody(required = false) StartScraperRunRequest request) {
        return scraperRunService.start(request == null ? null : request.region());
    }

    @PatchMapping("/{id}")
    public ScraperRunDto complete(@PathVariable Long id, @RequestBody CompleteScraperRunRequest request) {
        return scraperRunService.complete(id, request.status(), request.discovered(), request.newCount(), request.accepted());
    }

    @GetMapping("/next-region")
    public NextRegionResponse nextRegion(@RequestParam List<String> candidates) {
        return new NextRegionResponse(scraperRunService.nextRegion(candidates));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(NoSuchElementException e) {
        return Map.of("error", e.getMessage());
    }
}
