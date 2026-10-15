package com.everychurch.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** Atomic PostgreSQL counters shared by every application instance. */
@Repository
public class RateLimitRepository {

    private final JdbcTemplate jdbcTemplate;

    public RateLimitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int increment(String bucketKey, Instant windowStart) {
        Integer count = jdbcTemplate.queryForObject("""
            INSERT INTO rate_limit_bucket (bucket_key, window_start, request_count)
            VALUES (?, ?, 1)
            ON CONFLICT (bucket_key) DO UPDATE SET
                window_start = GREATEST(rate_limit_bucket.window_start, EXCLUDED.window_start),
                request_count = CASE
                    WHEN rate_limit_bucket.window_start < EXCLUDED.window_start THEN 1
                    WHEN rate_limit_bucket.window_start = EXCLUDED.window_start
                        THEN rate_limit_bucket.request_count + 1
                    ELSE rate_limit_bucket.request_count
                END
            RETURNING request_count
            """, Integer.class, bucketKey, Timestamp.from(windowStart));
        if (count == null) {
            throw new IllegalStateException("Rate-limit counter did not return a value");
        }
        return count;
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbcTemplate.update(
            "DELETE FROM rate_limit_bucket WHERE window_start < ?",
            Timestamp.from(cutoff));
    }
}
