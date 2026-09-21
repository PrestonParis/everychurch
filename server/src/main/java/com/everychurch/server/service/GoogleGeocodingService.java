package com.everychurch.server.service;

import com.everychurch.server.dto.GeocodeApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Google Geocoding API — same provider as the Maps usage described elsewhere in the
 * brief. Requires GOOGLE_GEOCODING_API_KEY; when unset, {@link #geocode} always
 * returns empty rather than failing, so churches still save without coordinates.
 */
@Service
public class GoogleGeocodingService implements GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GoogleGeocodingService.class);

    private final RestClient restClient;
    private final String apiKey;

    public GoogleGeocodingService(@Value("${google.geocoding.api-key:}") String apiKey) {
        // Built directly rather than via an injected RestClient.Builder — this Spring
        // Boot version doesn't auto-configure one from spring-boot-starter-web alone,
        // and a single hardcoded-base-URL client doesn't need the shared builder anyway.
        this.restClient = RestClient.builder().baseUrl("https://maps.googleapis.com/maps/api/geocode/json").build();
        this.apiKey = apiKey;
    }

    @Override
    public Optional<double[]> geocode(String address) {
        if (apiKey == null || apiKey.isBlank() || address == null || address.isBlank()) {
            return Optional.empty();
        }

        try {
            GeocodeApiResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.queryParam("address", address).queryParam("key", apiKey).build())
                .retrieve()
                .body(GeocodeApiResponse.class);

            if (response == null || !"OK".equals(response.status()) || response.results() == null || response.results().isEmpty()) {
                log.warn("Geocoding returned no result for address '{}': status={}", address, response != null ? response.status() : "null response");
                return Optional.empty();
            }

            GeocodeApiResponse.Location location = response.results().get(0).geometry().location();
            return Optional.of(new double[]{location.lat(), location.lng()});
        } catch (Exception e) {
            log.warn("Geocoding request failed for address '{}': {}", address, e.getMessage());
            return Optional.empty();
        }
    }
}
