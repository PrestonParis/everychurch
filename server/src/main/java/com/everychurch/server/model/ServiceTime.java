package com.everychurch.server.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "service_times")
public class ServiceTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "church_id", nullable = false)
    @JsonIgnore
    private Church church;

    private String dayOfWeek;
    private String time;
    private String type;
    private String language;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Church getChurch() { return church; }
    public void setChurch(Church church) { this.church = church; }

    public String getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(String dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
}
