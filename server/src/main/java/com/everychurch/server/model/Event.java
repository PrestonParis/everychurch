package com.everychurch.server.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Instant startsAt;

    private Instant endsAt;

    /** Co-hosting churches, each with its own consent state — see EventCoHost's class
     *  comment. Replaced a plain @ManyToMany in V18 (found in review: no consent was
     *  required from a named co-host at all). A staff member with MANAGE_EVENTS on any
     *  one CONFIRMED (or, per EventService.requireCanManage, any) participating church
     *  may manage the event. */
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<EventCoHost> coHosts = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }

    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }

    public List<EventCoHost> getCoHosts() { return coHosts; }
    public void setCoHosts(List<EventCoHost> coHosts) { this.coHosts = coHosts; }
}
