package com.everychurch.server.service;

import com.everychurch.server.dto.ImportStatusDto;
import org.springframework.stereotype.Service;

/**
 * In-memory (not persisted) tracking of "is a bulk import running right now" — see
 * ImportStatusController for why this stays transient rather than a ScraperRun-style
 * table: it's operational telemetry for a live-progress UI, not data anyone reviews
 * historically, so there's nothing a migration/table would buy here. A restart just
 * resets to "not running," which is correct — nothing was actually in flight anymore.
 *
 * BulkImportController calls recordBatch() after every batch it receives (see
 * BulkImportRequest's optional source/totalNew fields, sent by bulk_import.py).
 * Flips to "running" on the first call since the last completion, using that call's
 * totalNew as the target; flips back once processedRows reaches it. A caller that
 * never sends totalNew (source/totalNew both null) never toggles this at all —
 * status just stays at its last known state, so an old/unaware caller can't leave it
 * stuck "running" forever.
 */
@Service
public class BulkImportStatusService {

    private boolean running = false;
    private String source;
    private Integer totalRows;
    private int processedRows;
    private boolean failed;

    public synchronized void recordBatch(String source, Integer totalNew, int batchSize, int createdInBatch) {
        if (source == null || totalNew == null) return; // caller isn't participating in progress reporting

        if (!running) {
            running = true;
            this.source = source;
            this.totalRows = totalNew;
            this.processedRows = 0;
            this.failed = false;
        }

        this.processedRows += batchSize;
        if (this.processedRows >= this.totalRows) {
            running = false;
        }
    }

    public synchronized void markFailed() {
        running = false;
        failed = true;
    }

    public synchronized ImportStatusDto snapshot(long churchCount) {
        return new ImportStatusDto(churchCount, running, totalRows, processedRows, failed);
    }
}
