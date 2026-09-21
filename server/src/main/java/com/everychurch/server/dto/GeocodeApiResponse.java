package com.everychurch.server.dto;

import java.util.List;

/** Shape of the Google Geocoding API JSON response — only the fields we use. */
public record GeocodeApiResponse(String status, List<Result> results) {
    public record Result(Geometry geometry) {}
    public record Geometry(Location location) {}
    public record Location(double lat, double lng) {}
}
