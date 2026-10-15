-- Distributed fixed-window abuse controls. One row is retained per hashed limiter key,
-- so Cloud Run instances share counters without storing raw IP addresses or account ids.
CREATE TABLE rate_limit_bucket (
    bucket_key VARCHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0)
);

CREATE INDEX idx_rate_limit_bucket_window_start
    ON rate_limit_bucket (window_start);
