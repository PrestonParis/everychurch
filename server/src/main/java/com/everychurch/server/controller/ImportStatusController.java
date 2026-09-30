package com.everychurch.server.controller;

import com.everychurch.server.dto.ImportStatusDto;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.service.BulkImportStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public (see SecurityConfig) — modeled on PublicConfigController. Lets the client's
 *  cold-start onboarding screen (church-list.component.ts) know whether the
 *  directory looks freshly-empty and, if so, whether a bulk import is in progress. */
@RestController
public class ImportStatusController {

    private final BulkImportStatusService bulkImportStatusService;
    private final ChurchRepository churchRepository;

    public ImportStatusController(BulkImportStatusService bulkImportStatusService, ChurchRepository churchRepository) {
        this.bulkImportStatusService = bulkImportStatusService;
        this.churchRepository = churchRepository;
    }

    @GetMapping("/api/import-status")
    public ImportStatusDto status() {
        return bulkImportStatusService.snapshot(churchRepository.count());
    }
}
