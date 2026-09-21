package com.everychurch.server.controller;

import com.everychurch.server.dto.ScraperSubmissionBatchRequest;
import com.everychurch.server.dto.ScraperSubmissionBatchResponse;
import com.everychurch.server.service.ChurchSubmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The scraper's ingestion endpoint (docs/SCRAPER.md). Authenticated via the
 * X-Scraper-Api-Key header (ScraperApiKeyFilter + SecurityConfig's
 * hasRole("SCRAPER") rule), not a user JWT.
 */
@RestController
@RequestMapping("/api/scraper/church-submissions")
public class ScraperSubmissionController {

    private final ChurchSubmissionService submissionService;

    public ScraperSubmissionController(ChurchSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScraperSubmissionBatchResponse submitBatch(@RequestBody ScraperSubmissionBatchRequest request) {
        int accepted = 0;
        for (var proposed : request.submissions()) {
            submissionService.submitFromScraper(proposed);
            accepted++;
        }
        return new ScraperSubmissionBatchResponse(accepted);
    }
}
