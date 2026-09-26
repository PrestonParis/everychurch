package com.everychurch.server.dto;

/** status is "ACTIONED" or "DISMISSED" (ReportStatus — never "OPEN", that's only the
 *  starting state a report is created in). */
public record ResolveReportRequest(String status) {}
