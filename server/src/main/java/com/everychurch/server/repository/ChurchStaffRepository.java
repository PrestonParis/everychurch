package com.everychurch.server.repository;

import com.everychurch.server.model.ChurchStaff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChurchStaffRepository extends JpaRepository<ChurchStaff, Long> {
    List<ChurchStaff> findByChurchId(Long churchId);
    List<ChurchStaff> findByUserId(Long userId);
    Optional<ChurchStaff> findByChurchIdAndUserId(Long churchId, Long userId);
    boolean existsByChurchIdAndUserId(Long churchId, Long userId);
}
