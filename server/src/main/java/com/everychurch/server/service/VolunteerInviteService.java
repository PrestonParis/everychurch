package com.everychurch.server.service;

import com.everychurch.server.dto.VolunteerInviteResponse;
import com.everychurch.server.model.User;
import com.everychurch.server.model.VolunteerInvite;
import com.everychurch.server.model.VolunteerInviteStatus;
import com.everychurch.server.repository.VolunteerInviteRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/** Admin-side lifecycle (generate/revoke/list) for volunteer invite codes. Redeeming
 * a code happens as part of registration — see AuthService.registerVolunteer. */
@Service
public class VolunteerInviteService {

    // Excludes visually ambiguous characters (0/O, 1/I) since these get read aloud
    // or copied from a printed card in the field.
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VolunteerInviteRepository inviteRepository;

    public VolunteerInviteService(VolunteerInviteRepository inviteRepository) {
        this.inviteRepository = inviteRepository;
    }

    public VolunteerInviteResponse generate(User admin, Long expiresInHours) {
        VolunteerInvite invite = new VolunteerInvite();
        invite.setCode(generateUniqueCode());
        invite.setCreatedBy(admin);
        if (expiresInHours != null) {
            invite.setExpiresAt(Instant.now().plusSeconds(expiresInHours * 3600));
        }
        inviteRepository.save(invite);
        return toResponse(invite);
    }

    public List<VolunteerInviteResponse> listAll() {
        return inviteRepository.findAll().stream().map(this::toResponse).toList();
    }

    public VolunteerInviteResponse revoke(Long id) {
        VolunteerInvite invite = inviteRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("No such invite"));

        if (invite.getStatus() != VolunteerInviteStatus.PENDING) {
            throw new IllegalStateException("Only a pending invite can be revoked");
        }

        invite.setStatus(VolunteerInviteStatus.REVOKED);
        inviteRepository.save(invite);
        return toResponse(invite);
    }

    private String generateUniqueCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            code = sb.toString();
        } while (inviteRepository.findByCode(code).isPresent());
        return code;
    }

    private VolunteerInviteResponse toResponse(VolunteerInvite invite) {
        return new VolunteerInviteResponse(
            invite.getId(), invite.getCode(), invite.getStatus(),
            invite.getCreatedAt(), invite.getExpiresAt(), invite.getRedeemedAt()
        );
    }
}
