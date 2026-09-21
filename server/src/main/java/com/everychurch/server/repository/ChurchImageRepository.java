package com.everychurch.server.repository;

import com.everychurch.server.model.ChurchImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChurchImageRepository extends JpaRepository<ChurchImage, Long> {
    List<ChurchImage> findByChurchIdOrderBySortOrderAsc(Long churchId);
}
