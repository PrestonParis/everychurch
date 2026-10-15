package com.everychurch.server.dto;

import java.util.List;

/** Each church must carry the {@code id} of an existing row; only its non-blank
 *  fields are considered (see ChurchService.fillBlanks). {@code source} is for the
 *  server log only. */
public record BulkFillRequest(List<ChurchDto> churches, String source) {}
