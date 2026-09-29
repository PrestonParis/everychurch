package com.everychurch.server.service;

import com.everychurch.server.dto.ClaimDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.Claim;
import com.everychurch.server.model.ClaimStatus;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ClaimRepository;
import com.everychurch.server.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final ChurchRepository churchRepository;
    private final UserRepository userRepository;
    private final ChurchStaffService churchStaffService;

    public ClaimService(
        ClaimRepository claimRepository,
        ChurchRepository churchRepository,
        UserRepository userRepository,
        ChurchStaffService churchStaffService
    ) {
        this.claimRepository = claimRepository;
        this.churchRepository = churchRepository;
        this.userRepository = userRepository;
        this.churchStaffService = churchStaffService;
    }

    public ClaimDto requestClaim(Long churchId, User requester, String notes) {
        Church church = churchRepository.findById(churchId)
            .orElseThrow(() -> new NoSuchElementException("No such church"));

        if (church.isClaimed()) {
            throw new IllegalStateException("This church has already been claimed");
        }
        if (claimRepository.existsByChurchIdAndUserIdAndStatus(churchId, requester.getId(), ClaimStatus.PENDING)) {
            throw new IllegalStateException("You already have a pending claim on this church");
        }

        Claim claim = new Claim();
        claim.setChurch(church);
        claim.setUser(requester);
        claim.setNotes(notes);
        return toDto(claimRepository.save(claim));
    }

    public List<ClaimDto> list(ClaimStatus statusFilter) {
        List<Claim> claims = statusFilter != null ? claimRepository.findByStatus(statusFilter) : claimRepository.findAll();
        return claims.stream().map(this::toDto).toList();
    }

    /** The claims a user has submitted themselves, newest first — distinct from the
     *  admin-only list() above, which returns every claim regardless of requester. */
    public List<ClaimDto> listMine(User requester) {
        return claimRepository.findByUserIdOrderByCreatedAtDesc(requester.getId()).stream().map(this::toDto).toList();
    }

    @Transactional
    public ClaimDto approve(Long claimId, User admin) {
        Claim claim = pendingClaimOrThrow(claimId);

        claim.setStatus(ClaimStatus.APPROVED);
        claim.setDecidedBy(admin);
        claim.setDecidedAt(Instant.now());

        Church church = claim.getChurch();
        church.setClaimed(true);
        churchRepository.save(church);

        User requester = claim.getUser();
        if (requester.getRole() == Role.MEMBER) {
            requester.setRole(Role.CHURCH_ADMIN);
            userRepository.save(requester);
        }

        churchStaffService.grantOwnership(church, requester);

        return toDto(claimRepository.save(claim));
    }

    public ClaimDto reject(Long claimId, User admin) {
        Claim claim = pendingClaimOrThrow(claimId);
        claim.setStatus(ClaimStatus.REJECTED);
        claim.setDecidedBy(admin);
        claim.setDecidedAt(Instant.now());
        return toDto(claimRepository.save(claim));
    }

    private Claim pendingClaimOrThrow(Long claimId) {
        Claim claim = claimRepository.findById(claimId)
            .orElseThrow(() -> new NoSuchElementException("No such claim"));
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new IllegalStateException("This claim has already been decided");
        }
        return claim;
    }

    private ClaimDto toDto(Claim claim) {
        return new ClaimDto(
            claim.getId(),
            claim.getChurch().getId(),
            claim.getChurch().getName(),
            claim.getUser().getEmail(),
            claim.getStatus(),
            claim.getNotes(),
            claim.getDecidedBy() != null ? claim.getDecidedBy().getEmail() : null,
            claim.getCreatedAt(),
            claim.getDecidedAt()
        );
    }
}
