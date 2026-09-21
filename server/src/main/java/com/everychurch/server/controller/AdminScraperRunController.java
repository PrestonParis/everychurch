package com.everychurch.server.controller;

import com.everychurch.server.dto.ScraperRunDto;
import com.everychurch.server.dto.TriggerScraperRunRequest;
import com.everychurch.server.dto.TriggerScraperRunResponse;
import com.everychurch.server.service.ScraperRunService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** /api/admin/** requires ROLE_PLATFORM_ADMIN — see SecurityConfig. */
@RestController
@RequestMapping("/api/admin/scraper-runs")
public class AdminScraperRunController {

    private final ScraperRunService scraperRunService;

    public AdminScraperRunController(ScraperRunService scraperRunService) {
        this.scraperRunService = scraperRunService;
    }

    @GetMapping
    public List<ScraperRunDto> list() {
        return scraperRunService.list();
    }

    @PostMapping("/trigger")
    public TriggerScraperRunResponse trigger(@RequestBody(required = false) TriggerScraperRunRequest request) {
        return scraperRunService.trigger(request == null ? null : request.region());
    }
}
