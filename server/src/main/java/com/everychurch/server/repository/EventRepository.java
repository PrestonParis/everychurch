package com.everychurch.server.repository;

import com.everychurch.server.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findByChurchesId(Long churchId);
}
