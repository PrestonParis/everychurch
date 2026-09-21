package com.everychurch.server;

import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Nothing else can create the first Platform Admin — /api/admin/** requires
 * ROLE_PLATFORM_ADMIN, and there's no public path to that role. Seeds one from
 * ADMIN_EMAIL/ADMIN_PASSWORD on startup if no Platform Admin exists yet, so the
 * volunteer-invite flow (Prompt 7) has someone able to generate invites.
 */
@Component
public class AdminUserSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminUserSeeder(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        @Value("${admin.seed.email}") String adminEmail,
        @Value("${admin.seed.password}") String adminPassword
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (userRepository.existsByRole(Role.PLATFORM_ADMIN)) return;

        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setRole(Role.PLATFORM_ADMIN);
        userRepository.save(admin);
    }
}
