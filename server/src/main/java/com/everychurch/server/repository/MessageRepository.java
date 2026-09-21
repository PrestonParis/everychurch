package com.everychurch.server.repository;

import com.everychurch.server.model.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    /** Newest first; the Pageable only carries the limit — ordering is fixed by the method name. */
    List<Message> findByChurchIdOrderByIdDesc(Long churchId, Pageable pageable);

    List<Message> findByChurchIdAndIdLessThanOrderByIdDesc(Long churchId, Long beforeId, Pageable pageable);
}
