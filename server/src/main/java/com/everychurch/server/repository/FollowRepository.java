package com.everychurch.server.repository;

import com.everychurch.server.model.Follow;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FollowRepository extends JpaRepository<Follow, Long> {
    boolean existsByChurchIdAndUserId(Long churchId, Long userId);
    Optional<Follow> findByChurchIdAndUserId(Long churchId, Long userId);

    @EntityGraph(attributePaths = "church")
    List<Follow> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByChurchId(Long churchId);
}
