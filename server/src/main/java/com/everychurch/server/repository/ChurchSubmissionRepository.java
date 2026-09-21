package com.everychurch.server.repository;

import com.everychurch.server.model.ChurchSubmission;
import com.everychurch.server.model.ChurchSubmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChurchSubmissionRepository extends JpaRepository<ChurchSubmission, Long> {
    List<ChurchSubmission> findByStatus(ChurchSubmissionStatus status);
}
