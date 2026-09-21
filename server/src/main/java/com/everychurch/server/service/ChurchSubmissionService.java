package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.ChurchSubmissionDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.ChurchSubmission;
import com.everychurch.server.model.ChurchSubmissionStatus;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchSubmissionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The Volunteer submission-and-verification queue (docs/DATA_MODEL.md `ChurchSubmission`).
 * Volunteers (and, once Prompt 17 lands, the scraper) propose new churches or edits to
 * existing ones; nothing they submit touches the live Church record until a Platform
 * Admin approves it.
 */
@Service
public class ChurchSubmissionService {

    private final ChurchSubmissionRepository submissionRepository;
    private final ChurchRepository churchRepository;
    private final ChurchService churchService;
    // Built directly rather than injected — no ObjectMapper bean is auto-configured in
    // this setup (see UrlParserService, which does the same for the same reason).
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChurchSubmissionService(
        ChurchSubmissionRepository submissionRepository,
        ChurchRepository churchRepository,
        ChurchService churchService
    ) {
        this.submissionRepository = submissionRepository;
        this.churchRepository = churchRepository;
        this.churchService = churchService;
    }

    public ChurchSubmissionDto submitNewChurch(ChurchDto proposed, User volunteer) {
        requireVolunteer(volunteer);

        ChurchSubmission submission = new ChurchSubmission();
        submission.setTargetChurch(null);
        submission.setPayload(writePayload(proposed));
        submission.setSubmittedBy(String.valueOf(volunteer.getId()));
        return toDto(submissionRepository.save(submission));
    }

    public ChurchSubmissionDto submitEdit(Long targetChurchId, ChurchDto proposed, User volunteer) {
        requireVolunteer(volunteer);

        Church targetChurch = churchRepository.findById(targetChurchId)
            .orElseThrow(() -> new NoSuchElementException("No such church: " + targetChurchId));

        ChurchSubmission submission = new ChurchSubmission();
        submission.setTargetChurch(targetChurch);
        submission.setPayload(writePayload(proposed));
        submission.setSubmittedBy(String.valueOf(volunteer.getId()));
        return toDto(submissionRepository.save(submission));
    }

    /**
     * The scraper's ingestion path (docs/SCRAPER.md) — no User to attribute this to,
     * so submittedBy is the literal string "scraper" rather than a user id. Always a
     * new-church proposal: the scraper's own dedup pass (dedup.filter_new) already
     * drops anything that looks like an existing Church before it gets here, so there's
     * no targetChurchId concept on this path.
     */
    public ChurchSubmissionDto submitFromScraper(ChurchDto proposed) {
        ChurchSubmission submission = new ChurchSubmission();
        submission.setTargetChurch(null);
        submission.setPayload(writePayload(proposed));
        submission.setSubmittedBy("scraper");
        return toDto(submissionRepository.save(submission));
    }

    @Transactional(readOnly = true)
    public List<ChurchSubmissionDto> list(ChurchSubmissionStatus statusFilter) {
        List<ChurchSubmission> submissions = statusFilter != null
            ? submissionRepository.findByStatus(statusFilter)
            : submissionRepository.findAll();
        return submissions.stream().map(this::toDto).toList();
    }

    @Transactional
    public ChurchSubmissionDto approve(Long submissionId, User admin) {
        ChurchSubmission submission = pendingSubmissionOrThrow(submissionId);
        ChurchDto proposed = readPayload(submission.getPayload());

        if (submission.getTargetChurch() == null) {
            ChurchDto created = churchService.create(proposed);
            submission.setTargetChurch(churchRepository.getReferenceById(created.id()));
        } else {
            churchService.update(submission.getTargetChurch().getId(), proposed, admin);
        }

        submission.setStatus(ChurchSubmissionStatus.APPROVED);
        submission.setReviewedBy(admin);
        submission.setReviewedAt(Instant.now());
        return toDto(submissionRepository.save(submission));
    }

    public ChurchSubmissionDto reject(Long submissionId, User admin) {
        ChurchSubmission submission = pendingSubmissionOrThrow(submissionId);
        submission.setStatus(ChurchSubmissionStatus.REJECTED);
        submission.setReviewedBy(admin);
        submission.setReviewedAt(Instant.now());
        return toDto(submissionRepository.save(submission));
    }

    private void requireVolunteer(User requester) {
        if (requester.getRole() != Role.VOLUNTEER) {
            throw new AccessDeniedException("Only volunteers can submit church data for review");
        }
    }

    private ChurchSubmission pendingSubmissionOrThrow(Long submissionId) {
        ChurchSubmission submission = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new NoSuchElementException("No such submission: " + submissionId));
        if (submission.getStatus() != ChurchSubmissionStatus.PENDING) {
            throw new IllegalStateException("This submission has already been decided");
        }
        return submission;
    }

    private String writePayload(ChurchDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize the proposed changes: " + e.getMessage());
        }
    }

    private ChurchDto readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, ChurchDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored submission payload is corrupt: " + e.getMessage());
        }
    }

    private ChurchSubmissionDto toDto(ChurchSubmission submission) {
        return new ChurchSubmissionDto(
            submission.getId(),
            submission.getTargetChurch() != null ? submission.getTargetChurch().getId() : null,
            submission.getTargetChurch() != null ? submission.getTargetChurch().getName() : null,
            readPayload(submission.getPayload()),
            submission.getSubmittedBy(),
            submission.getStatus(),
            submission.getReviewedBy() != null ? submission.getReviewedBy().getEmail() : null,
            submission.getCreatedAt(),
            submission.getReviewedAt()
        );
    }
}
