package com.everychurch.server.dto;

import java.util.List;

public record BulkImportRequest(List<ChurchDto> churches) {}
