package com.everychurch.server.controller;

import com.everychurch.server.dto.PublicConfigDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A small, public (see SecurityConfig) set of client-safe config values — currently
 * just the Google Maps Embed key. That key is meant to be exposed to the browser
 * (restricted by HTTP referrer in Google Cloud Console, not by secrecy), but the
 * client has no build-time env substitution set up, so it fetches it here instead
 * of it being baked into the Angular bundle.
 */
@RestController
public class PublicConfigController {

    private final String googleMapsEmbedKey;

    public PublicConfigController(@Value("${google.maps.embed-api-key:}") String googleMapsEmbedKey) {
        this.googleMapsEmbedKey = googleMapsEmbedKey;
    }

    @GetMapping("/api/config")
    public PublicConfigDto config() {
        return new PublicConfigDto(googleMapsEmbedKey.isBlank() ? null : googleMapsEmbedKey);
    }
}
