package com.everychurch.server.dto;

/** {@code region} is optional — omitted means "pick whichever candidate needs a run next." */
public record TriggerScraperRunRequest(String region) {}
