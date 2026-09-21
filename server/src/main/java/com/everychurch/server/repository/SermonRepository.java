package com.everychurch.server.repository;

import com.everychurch.server.model.Sermon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SermonRepository extends JpaRepository<Sermon, Long> {
    List<Sermon> findByChurchIdOrderByDateDesc(Long churchId);
}
