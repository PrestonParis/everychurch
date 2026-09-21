package com.everychurch.server.dto;

/** Body for both POST /api/submissions (new church) and POST /api/churches/{id}/submissions (edit). */
public record ChurchSubmissionRequest(ChurchDto proposedChanges) {}
