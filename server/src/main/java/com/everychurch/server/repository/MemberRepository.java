package com.everychurch.server.repository;

import com.everychurch.server.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    List<Member> findByChurchIdOrderByJoinedAtAsc(Long churchId);
    Optional<Member> findByChurchIdAndUserId(Long churchId, Long userId);
    boolean existsByChurchIdAndUserId(Long churchId, Long userId);
}
