package com.everychurch.server.service;

/**
 * Triggers a scraper run for a region. {@link LoggingScraperInvoker} is the only
 * implementation for now — there's no deployed Lambda to call yet (Prompt 6). A
 * later {@code LambdaScraperInvoker} (AWS SDK LambdaClient.invoke(...)) is meant to
 * be a one-class swap: same interface, real dispatch instead of a log line.
 */
public interface ScraperInvoker {
    /** Returns a human-readable status message describing what happened (or what a human still needs to do). */
    String trigger(String region, long runId);
}
