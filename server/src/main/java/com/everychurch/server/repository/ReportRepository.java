package com.everychurch.server.repository;

import com.everychurch.server.model.Report;
import com.everychurch.server.model.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRepository extends JpaRepository<Report, Long> {
    List<Report> findByStatusOrderByCreatedAtDesc(ReportStatus status);
    List<Report> findAllByOrderByCreatedAtDesc();
}
