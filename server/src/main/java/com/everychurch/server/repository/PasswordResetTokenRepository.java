package com.everychurch.server.repository;

import com.everychurch.server.model.PasswordResetToken;
import com.everychurch.server.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    // Run before issuing a new token (AuthService.forgotPassword) so a user only ever
    // has one outstanding, valid reset link at a time — requesting a new one silently
    // invalidates any earlier link still sitting in an old email.
    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.user = :user AND t.usedAt IS NULL")
    void deleteUnusedByUser(@Param("user") User user);
}
