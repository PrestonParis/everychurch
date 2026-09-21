package com.everychurch.server.controller;

import com.everychurch.server.dto.BulkImportRequest;
import com.everychurch.server.dto.BulkImportResponse;
import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.service.ChurchService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one-time bulk-import path (Prompt 7, docs/SCRAPER.md "Bulk import" /
 * docs/DATA_MODEL.md's "trusted bulk seed" distinction). Authenticated via
 * X-Bulk-Import-Api-Key (BulkImportApiKeyFilter + SecurityConfig's
 * hasRole("BULK_IMPORTER") rule) — a SEPARATE, independently-revocable secret from
 * the live scraper's, since this writes Church rows directly via ChurchService.create
 * (which already defaults claimed=false and runs geocoding), skipping the
 * ChurchSubmission review queue entirely rather than going through it.
 */
@RestController
@RequestMapping("/api/scraper/bulk-import")
public class BulkImportController {

    private final ChurchService churchService;

    public BulkImportController(ChurchService churchService) {
        this.churchService = churchService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BulkImportResponse importBatch(@RequestBody BulkImportRequest request) {
        int created = 0;
        for (ChurchDto proposed : request.churches()) {
            churchService.create(proposed);
            created++;
        }
        return new BulkImportResponse(created);
    }
}
