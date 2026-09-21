package com.everychurch.server.service;

import java.util.Optional;

/** Resolves a free-text address to [latitude, longitude]. See docs/ARCHITECTURE.md "Geocoding". */
public interface GeocodingService {
    Optional<double[]> geocode(String address);
}
