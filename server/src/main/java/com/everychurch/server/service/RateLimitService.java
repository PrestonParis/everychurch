package com.everychurch.server.service;

import com.everychurch.server.repository.RateLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database-backed fixed-window limiter. Keys are SHA-256 hashes so the database never
 * stores raw client IPs, email addresses, or user ids in this operational table.
 */
@Service
public class RateLimitService {

    private static final long CLEANUP_INTERVAL = 1_024;

    private final RateLimitRepository repository;
    private final Clock clock;
    private final boolean enabled;
    private final AtomicLong operations = new AtomicLong();

    @Autowired
    public RateLimitService(
        RateLimitRepository repository,
        @Value("${rate-limit.enabled:true}") boolean enabled
    ) {
        this(repository, Clock.systemUTC(), enabled);
    }

    RateLimitService(RateLimitRepository repository, Clock clock, boolean enabled) {
        this.repository = repository;
        this.clock = clock;
        this.enabled = enabled;
    }

    public Decision tryConsume(String scope, String identity, int limit, Duration window) {
        if (!enabled) return new Decision(true, 0);
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit configuration must be positive");
        }

        Instant now = clock.instant();
        long windowMillis = window.toMillis();
        long startMillis = Math.floorDiv(now.toEpochMilli(), windowMillis) * windowMillis;
        Instant windowStart = Instant.ofEpochMilli(startMillis);
        int count = repository.increment(hash(scope + ":" + identity), windowStart);

        if (operations.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            repository.deleteOlderThan(now.minus(Duration.ofDays(2)));
        }

        long retryAfter = Math.max(1, (windowStart.plus(window).toEpochMilli() - now.toEpochMilli() + 999) / 1_000);
        return new Decision(count <= limit, retryAfter);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {}
}
