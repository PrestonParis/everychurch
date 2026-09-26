package com.everychurch.server.repository;

import com.everychurch.server.model.ChurchView;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChurchViewRepository extends JpaRepository<ChurchView, Long> {
    Optional<ChurchView> findByChurchIdAndUserId(Long churchId, Long userId);

    @EntityGraph(attributePaths = "church")
    List<ChurchView> findByUserIdOrderByViewedAtDesc(Long userId, Pageable pageable);
}
