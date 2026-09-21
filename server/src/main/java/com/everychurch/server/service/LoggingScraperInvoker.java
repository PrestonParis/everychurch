package com.everychurch.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * No deployed Lambda exists yet, so "triggering" a run just records it (the caller
 * already did that via ScraperRunService.start) and logs that a human needs to
 * actually run the scraper locally — pretending to invoke a Lambda that doesn't
 * exist would be dishonest. Swap this for a LambdaScraperInvoker once one is
 * deployed; ScraperRunService doesn't need to change.
 */
@Service
public class LoggingScraperInvoker implements ScraperInvoker {

    private static final Logger log = LoggerFactory.getLogger(LoggingScraperInvoker.class);

    @Override
    public String trigger(String region, long runId) {
        log.info("Scraper run #{} recorded for region {} — no Lambda deployed; run it locally to fulfil it.", runId, region);
        return "Run #" + runId + " recorded for region " + region + ". No scraper Lambda is deployed yet — "
            + "a human needs to run it locally to fulfil this request, e.g. from apps/scraper: "
            + "`poetry run python -c \"from scraper.pipeline import run; run(region='" + region + "')\"`.";
    }
}
