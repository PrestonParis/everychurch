package com.everychurch.server.dto;

import java.util.List;

/** source/totalNew are optional progress-reporting fields (see
 *  BulkImportStatusService) — a caller that omits them (e.g. an older script, or
 *  hifld_import.py before it's updated) still imports normally, just without
 *  updating the client-visible progress. */
public record BulkImportRequest(List<ChurchDto> churches, String source, Integer totalNew) {}
