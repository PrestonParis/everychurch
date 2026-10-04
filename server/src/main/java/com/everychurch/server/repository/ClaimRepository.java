package com.everychurch.server.repository;

import com.everychurch.server.model.Claim;
import com.everychurch.server.model.ClaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaimRepository extends JpaRepository<Claim, Long> {
    List<Claim> findByStatus(ClaimStatus status);
    List<Claim> findByChurchIdAndStatus(Long churchId, ClaimStatus status);
    boolean existsByChurchIdAndUserIdAndStatus(Long churchId, Long userId, ClaimStatus status);
    List<Claim> findByUserIdOrderByCreatedAtDesc(Long userId);
}
