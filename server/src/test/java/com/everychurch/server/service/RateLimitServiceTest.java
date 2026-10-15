package com.everychurch.server.service;

import com.everychurch.server.repository.RateLimitRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {

    private final RateLimitRepository repository = mock(RateLimitRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:30Z"), ZoneOffset.UTC);

    @Test
    void permitsRequestsThroughTheLimitAndRejectsTheNext() {
        when(repository.increment(anyString(), any())).thenReturn(1, 2, 3);
        RateLimitService limiter = new RateLimitService(repository, clock, true);

        assertTrue(limiter.tryConsume("login", "192.0.2.10", 2, Duration.ofMinutes(1)).allowed());
        assertTrue(limiter.tryConsume("login", "192.0.2.10", 2, Duration.ofMinutes(1)).allowed());
        RateLimitService.Decision rejected =
            limiter.tryConsume("login", "192.0.2.10", 2, Duration.ofMinutes(1));
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterSeconds() > 0);
    }

    @Test
    void disabledLimiterDoesNotTouchTheDatabase() {
        RateLimitService limiter = new RateLimitService(repository, clock, false);
        assertTrue(limiter.tryConsume("login", "192.0.2.10", 1, Duration.ofMinutes(1)).allowed());
    }
}
