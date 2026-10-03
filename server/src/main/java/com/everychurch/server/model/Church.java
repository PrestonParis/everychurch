package com.everychurch.server.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "churches")
public class Church {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String denomination;

    private String address;
    private String city;
    private String state;
    private String zipCode;
    private String phone;
    private String email;
    private String website;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 1024)  // Commons thumbnail URLs run past 255 (V22)
    private String imageUrl;
    private String pastor;
    private String facebook;
    private String instagram;
    private String youtube;

    private boolean claimed = false;

    private Double latitude;
    private Double longitude;

    // Denormalized, recomputed by ChurchService.applyDto whenever serviceTimes are saved.
    // Exists purely so the paginated list can show something useful without loading the
    // LAZY serviceTimes collection per row — see the comment on that field below.
    private String serviceSummary;

    // LAZY on purpose: the paginated list/search/nearest endpoints (ChurchService)
    // never touch this association and map straight to an empty list instead — nobody
    // needs a church's full schedule to render a list card. GET /api/churches/{id} is
    // the only place it's actually used, and loads it explicitly via
    // ChurchRepository.findById's @EntityGraph rather than relying on EAGER here.
    @OneToMany(mappedBy = "church", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ServiceTime> serviceTimes = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDenomination() { return denomination; }
    public void setDenomination(String denomination) { this.denomination = denomination; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getZipCode() { return zipCode; }
    public void setZipCode(String zipCode) { this.zipCode = zipCode; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getPastor() { return pastor; }
    public void setPastor(String pastor) { this.pastor = pastor; }

    public String getFacebook() { return facebook; }
    public void setFacebook(String facebook) { this.facebook = facebook; }

    public String getInstagram() { return instagram; }
    public void setInstagram(String instagram) { this.instagram = instagram; }

    public String getYoutube() { return youtube; }
    public void setYoutube(String youtube) { this.youtube = youtube; }

    public boolean isClaimed() { return claimed; }
    public void setClaimed(boolean claimed) { this.claimed = claimed; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public String getServiceSummary() { return serviceSummary; }
    public void setServiceSummary(String serviceSummary) { this.serviceSummary = serviceSummary; }

    public List<ServiceTime> getServiceTimes() { return serviceTimes; }
    public void setServiceTimes(List<ServiceTime> serviceTimes) { this.serviceTimes = serviceTimes; }
}
