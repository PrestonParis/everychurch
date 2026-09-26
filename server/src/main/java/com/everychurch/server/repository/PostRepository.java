package com.everychurch.server.repository;

import com.everychurch.server.model.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    /** A single church's own posts (its profile page) — newest first. */
    List<Post> findByChurchIdOrderByIdDesc(Long churchId, Pageable pageable);

    List<Post> findByChurchIdAndIdLessThanOrderByIdDesc(Long churchId, Long beforeId, Pageable pageable);

    /** The follow feed: every post from a church the given user follows, newest
     *  first across all of them (not grouped/paged per church). */
    @Query("SELECT p FROM Post p WHERE p.church.id IN (SELECT f.church.id FROM Follow f WHERE f.user.id = :userId) ORDER BY p.id DESC")
    List<Post> findFeedForUser(@Param("userId") Long userId, Pageable pageable);

    @Query("""
        SELECT p FROM Post p
        WHERE p.church.id IN (SELECT f.church.id FROM Follow f WHERE f.user.id = :userId)
          AND p.id < :beforeId
        ORDER BY p.id DESC
        """)
    List<Post> findFeedForUserBefore(@Param("userId") Long userId, @Param("beforeId") Long beforeId, Pageable pageable);
}
