package com.everychurch.server.service;

import com.everychurch.server.dto.AuthResponse;
import com.everychurch.server.dto.ForgotPasswordRequest;
import com.everychurch.server.dto.LoginRequest;
import com.everychurch.server.dto.RegisterRequest;
import com.everychurch.server.dto.ResetPasswordRequest;
import com.everychurch.server.dto.VolunteerRegisterRequest;
import com.everychurch.server.model.PasswordResetToken;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.model.VolunteerInvite;
import com.everychurch.server.model.VolunteerInviteStatus;
import com.everychurch.server.repository.PasswordResetTokenRepository;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.repository.VolunteerInviteRepository;
import com.everychurch.server.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {

    private static final Duration RESET_TOKEN_TTL = Duration.ofHours(1);

    private final UserRepository userRepository;
    private final VolunteerInviteRepository inviteRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final String clientBaseUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
        UserRepository userRepository,
        VolunteerInviteRepository inviteRepository,
        PasswordResetTokenRepository resetTokenRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        MailService mailService,
        @Value("${app.client-base-url:http://localhost:4200}") String clientBaseUrl
    ) {
        this.userRepository = userRepository;
        this.inviteRepository = inviteRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mailService = mailService;
        this.clientBaseUrl = clientBaseUrl;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalStateException("An account with that email already exists");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.MEMBER);
        user.setDisplayName(requireDisplayName(request.displayName()));
        userRepository.save(user);

        return toAuthResponse(user);
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
        user.setDisplayName(requireDisplayName(request.displayName()));
        userRepository.save(user);

        invite.setStatus(VolunteerInviteStatus.REDEEMED);
        invite.setRedeemedBy(user);
        invite.setRedeemedAt(Instant.now());
        inviteRepository.save(invite);

        return toAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return toAuthResponse(user);
    }

    /**
     * Always succeeds from the caller's point of view, whether or not the email
     * belongs to an account — a "no such account" response here would let anyone
     * enumerate which emails are registered. If it does match, one valid reset link
     * is emailed (any earlier unused one for this user is invalidated first, so old
     * emails can't be replayed alongside a newer one).
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String email = request.email() == null ? "" : request.email().trim();
        if (email.isEmpty()) {
            return;
        }

        userRepository.findByEmail(email).ifPresent(user -> {
            resetTokenRepository.deleteUnusedByUser(user);

            String rawToken = generateRawToken();
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setUser(user);
            resetToken.setTokenHash(hashToken(rawToken));
            resetToken.setExpiresAt(Instant.now().plus(RESET_TOKEN_TTL));
            resetTokenRepository.save(resetToken);

            String resetLink = clientBaseUrl + "/reset-password?token=" + rawToken;
            mailService.sendPasswordResetEmail(user.getEmail(), resetLink);
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (request.newPassword() == null || request.newPassword().length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        String token = request.token() == null ? "" : request.token();

        PasswordResetToken resetToken = resetTokenRepository.findByTokenHash(hashToken(token))
            .filter(PasswordResetToken::isValid)
            .orElseThrow(() -> new IllegalArgumentException("This reset link is invalid or has expired"));

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        resetToken.setUsedAt(Instant.now());
        resetTokenRepository.save(resetToken);
        // Any other outstanding link for this user is now stale (the password it would
        // have reset no longer applies) — invalidate it too rather than leave it valid
        // for the next hour.
        resetTokenRepository.deleteUnusedByUser(user);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of the raw token — only this ever touches the database (see
     *  PasswordResetToken's class comment). Not the slow bcrypt-style PasswordEncoder
     *  used for actual passwords: this token is already 256 bits of random entropy, so
     *  a fast, deterministic hash (needed to look it up by exact match) is the right
     *  tool, the same tradeoff most frameworks make for bearer/API tokens. */
    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandatory algorithm — unreachable in practice.
            throw new IllegalStateException(e);
        }
    }

    /** Required at registration (chat/member-list identity — see User.getDisplayName). */
    private String requireDisplayName(String displayName) {
        String trimmed = displayName == null ? "" : displayName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A display name is required");
        }
        return trimmed;
    }

    private AuthResponse toAuthResponse(User user) {
        return new AuthResponse(
            jwtService.generateToken(user.getEmail(), user.getRole()),
            user.getId(), user.getEmail(), user.getRole(), user.getDisplayName(), user.getAvatarUrl()
        );
    }
}
