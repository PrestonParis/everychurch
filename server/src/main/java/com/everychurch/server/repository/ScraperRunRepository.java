package com.everychurch.server.repository;

import com.everychurch.server.model.ScraperRun;
import com.everychurch.server.model.ScraperRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScraperRunRepository extends JpaRepository<ScraperRun, Long> {
    List<ScraperRun> findAllByOrderByStartedAtDesc();

    Optional<ScraperRun> findFirstByRegionAndStatusOrderByCompletedAtDesc(String region, ScraperRunStatus status);
}
