package com.everychurch.server.dto;

import java.util.List;

/** Body for POST /api/scraper/church-submissions — see docs/SCRAPER.md. */
public record ScraperSubmissionBatchRequest(List<ChurchDto> submissions) {}
