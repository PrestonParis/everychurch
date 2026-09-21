package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A proposed create/edit of a Church, pending Platform Admin review. See
 * docs/DATA_MODEL.md `ChurchSubmission`.
 */
@Entity
@Table(name = "church_submissions")
public class ChurchSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_church_id")
    private Church targetChurch;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String submittedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChurchSubmissionStatus status = ChurchSubmissionStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private User reviewedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant reviewedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Church getTargetChurch() { return targetChurch; }
    public void setTargetChurch(Church targetChurch) { this.targetChurch = targetChurch; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }

    public ChurchSubmissionStatus getStatus() { return status; }
    public void setStatus(ChurchSubmissionStatus status) { this.status = status; }

    public User getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(User reviewedBy) { this.reviewedBy = reviewedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
}
