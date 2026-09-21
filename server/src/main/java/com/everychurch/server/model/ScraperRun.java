package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One scraper pipeline.run() invocation (see docs/SCRAPER.md). {@code region} is
 * nullable — a local/manual full-CONUS sweep (no region scoping) still gets tracked,
 * it just has no single region to attribute to. The count fields stay null until the
 * run completes or fails.
 */
@Entity
@Table(name = "scraper_runs")
public class ScraperRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String region;

    @Column(nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    private Integer discovered;
    private Integer newCount;
    private Integer accepted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScraperRunStatus status = ScraperRunStatus.RUNNING;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Integer getDiscovered() { return discovered; }
    public void setDiscovered(Integer discovered) { this.discovered = discovered; }

    public Integer getNewCount() { return newCount; }
    public void setNewCount(Integer newCount) { this.newCount = newCount; }

    public Integer getAccepted() { return accepted; }
    public void setAccepted(Integer accepted) { this.accepted = accepted; }

    public ScraperRunStatus getStatus() { return status; }
    public void setStatus(ScraperRunStatus status) { this.status = status; }
}
