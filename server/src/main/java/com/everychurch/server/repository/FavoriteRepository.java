package com.everychurch.server.repository;

import com.everychurch.server.model.Favorite;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {
    boolean existsByChurchIdAndUserId(Long churchId, Long userId);
    Optional<Favorite> findByChurchIdAndUserId(Long churchId, Long userId);

    // Eager-fetches church in the same query — listMyFavorites maps straight to
    // ChurchDto per row, so a lazy church would otherwise be one extra select per
    // favorite (N+1).
    @EntityGraph(attributePaths = "church")
    List<Favorite> findByUserIdOrderByCreatedAtDesc(Long userId);
}
