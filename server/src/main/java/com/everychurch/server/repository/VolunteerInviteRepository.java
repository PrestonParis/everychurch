package com.everychurch.server.repository;

import com.everychurch.server.model.VolunteerInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VolunteerInviteRepository extends JpaRepository<VolunteerInvite, Long> {
    Optional<VolunteerInvite> findByCode(String code);
}
