package com.everychurch.server.repository;

import com.everychurch.server.model.Block;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BlockRepository extends JpaRepository<Block, Long> {
    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);
    Optional<Block> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    /** True if EITHER user has blocked the other — DirectMessageService checks this
     *  before every send, since blocking is meant to stop the conversation from both
     *  sides, not just the blocker's. */
    @Query("""
        SELECT COUNT(b) > 0 FROM Block b
        WHERE (b.blocker.id = :userA AND b.blocked.id = :userB)
           OR (b.blocker.id = :userB AND b.blocked.id = :userA)
        """)
    boolean existsEitherDirection(@Param("userA") Long userA, @Param("userB") Long userB);
}
