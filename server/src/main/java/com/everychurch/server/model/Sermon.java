package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "sermons")
public class Sermon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "church_id", nullable = false)
    private Church church;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private LocalDate date;

    /** Points at object storage — see docs/ARCHITECTURE.md "Object Storage" (TBD, Prompt 12). */
    private String mediaUrl;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Church getChurch() { return church; }
    public void setChurch(Church church) { this.church = church; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; }
}
