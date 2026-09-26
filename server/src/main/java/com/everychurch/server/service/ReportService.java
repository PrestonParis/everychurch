package com.everychurch.server.service;

import com.everychurch.server.dto.ReportDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.Report;
import com.everychurch.server.model.ReportStatus;
import com.everychurch.server.model.ReportTargetType;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ReportRepository;
import com.everychurch.server.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/** Reports a Platform Admin reviews — see docs/DATA_MODEL.md `Report`. Creating one
 *  needs no special permission (any authenticated user can report anything), same as
 *  most "flag this" features; reviewing is admin-only (AdminReportController, gated
 *  by SecurityConfig's blanket /api/admin/** rule). */
@Service
@Transactional
public class ReportService {

    private static final int MAX_REASON_LENGTH = 2000;

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final ChurchRepository churchRepository;

    public ReportService(ReportRepository reportRepository, UserRepository userRepository, ChurchRepository churchRepository) {
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.churchRepository = churchRepository;
    }

    public ReportDto create(User reporter, String targetTypeRaw, Long targetId, String reason) {
        ReportTargetType targetType = parseTargetType(targetTypeRaw);
        if (targetId == null) {
            throw new IllegalArgumentException("targetId is required");
        }
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isEmpty()) {
            throw new IllegalArgumentException("A reason is required");
        }
        if (trimmedReason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Reason can be at most " + MAX_REASON_LENGTH + " characters");
        }
        // Confirmed to exist so admins never review a report against nothing.
        if (targetType == ReportTargetType.USER && !userRepository.existsById(targetId)) {
            throw new NoSuchElementException("No such user");
        }
        if (targetType == ReportTargetType.CHURCH && !churchRepository.existsById(targetId)) {
            throw new NoSuchElementException("No such church");
        }

        Report report = new Report();
        report.setReporter(reporter);
        report.setTargetType(targetType);
        report.setTargetId(targetId);
        report.setReason(trimmedReason);
        return toDto(reportRepository.save(report));
    }

    @Transactional(readOnly = true)
    public List<ReportDto> list(String statusFilter) {
        List<Report> reports = (statusFilter == null || statusFilter.isBlank())
            ? reportRepository.findAllByOrderByCreatedAtDesc()
            : reportRepository.findByStatusOrderByCreatedAtDesc(parseStatus(statusFilter));
        return reports.stream().map(this::toDto).toList();
    }

    public ReportDto resolve(Long id, User admin, String statusRaw) {
        ReportStatus status = parseStatus(statusRaw);
        if (status == ReportStatus.OPEN) {
            throw new IllegalArgumentException("A report can only be resolved to ACTIONED or DISMISSED");
        }
        Report report = reportRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("No such report"));

        report.setStatus(status);
        report.setResolvedAt(Instant.now());
        report.setResolvedBy(admin);
        return toDto(reportRepository.save(report));
    }

    private ReportTargetType parseTargetType(String raw) {
        try {
            return ReportTargetType.valueOf(raw == null ? "" : raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("targetType must be USER or CHURCH");
        }
    }

    private ReportStatus parseStatus(String raw) {
        try {
            return ReportStatus.valueOf(raw == null ? "" : raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be OPEN, ACTIONED, or DISMISSED");
        }
    }

    private ReportDto toDto(Report report) {
        String targetLabel = resolveTargetLabel(report.getTargetType(), report.getTargetId());
        return new ReportDto(
            report.getId(),
            report.getReporter().getId(), report.getReporter().getDisplayName(),
            report.getTargetType().name(), report.getTargetId(), targetLabel,
            report.getReason(),
            report.getStatus().name(),
            report.getCreatedAt(),
            report.getResolvedAt(), report.getResolvedBy() != null ? report.getResolvedBy().getDisplayName() : null
        );
    }

    /** The reported thing may since have been deleted (a Church removed, a User —
     *  hypothetically — gone) — a report against it should still be reviewable, just
     *  clearly labeled, rather than the whole list erroring out. */
    private String resolveTargetLabel(ReportTargetType type, Long id) {
        return switch (type) {
            case USER -> userRepository.findById(id).map(User::getDisplayName).orElse("(deleted user)");
            case CHURCH -> churchRepository.findById(id).map(Church::getName).orElse("(deleted church)");
        };
    }
}
