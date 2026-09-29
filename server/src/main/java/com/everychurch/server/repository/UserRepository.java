package com.everychurch.server.repository;

import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByRole(Role role);
    Optional<User> findByUsernameIgnoreCase(String username);
}
