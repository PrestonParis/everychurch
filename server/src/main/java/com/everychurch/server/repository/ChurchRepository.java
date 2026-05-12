package com.everychurch.server.repository;

import com.everychurch.server.model.Church;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChurchRepository extends JpaRepository<Church, Long> {
    List<Church> findByNameContainingIgnoreCaseOrCityContainingIgnoreCaseOrDenominationContainingIgnoreCase(
        String name, String city, String denomination
    );
}
