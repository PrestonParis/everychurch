package com.everychurch.server.service;

import com.everychurch.server.dto.AuthResponse;
import com.everychurch.server.dto.LoginRequest;
import com.everychurch.server.dto.RegisterRequest;
import com.everychurch.server.dto.VolunteerRegisterRequest;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.model.VolunteerInvite;
import com.everychurch.server.model.VolunteerInviteStatus;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.repository.VolunteerInviteRepository;
import com.everychurch.server.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final VolunteerInviteRepository inviteRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
        UserRepository userRepository,
        VolunteerInviteRepository inviteRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.inviteRepository = inviteRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalStateException("An account with that email already exists");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.MEMBER);
        userRepository.save(user);

        return new AuthResponse(jwtService.generateToken(user.getEmail(), user.getRole()), user.getEmail(), user.getRole());
    }

    /**
     * Volunteers can't self-register into Role.VOLUNTEER via the normal /register
     * endpoint — they need a code a Platform Admin generated for them (see
     * VolunteerInviteService and docs/ARCHITECTURE.md "Volunteer onboarding").
     */
    @Transactional
    public AuthResponse registerVolunteer(VolunteerRegisterRequest request) {
        VolunteerInvite invite = inviteRepository.findByCode(request.code())
            .orElseThrow(() -> new IllegalArgumentException("Invalid invite code"));

        if (invite.getStatus() != VolunteerInviteStatus.PENDING) {
            throw new IllegalArgumentException("This invite code has already been used or revoked");
        }
        if (invite.isExpired()) {
            throw new IllegalArgumentException("This invite code has expired");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalStateException("An account with that email already exists");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.VOLUNTEER);
        userRepository.save(user);

        invite.setStatus(VolunteerInviteStatus.REDEEMED);
        invite.setRedeemedBy(user);
        invite.setRedeemedAt(Instant.now());
        inviteRepository.save(invite);

        return new AuthResponse(jwtService.generateToken(user.getEmail(), user.getRole()), user.getEmail(), user.getRole());
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return new AuthResponse(jwtService.generateToken(user.getEmail(), user.getRole()), user.getEmail(), user.getRole());
    }
}
