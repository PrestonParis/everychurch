package com.everychurch.server.dto;

/** Just enough to render a church's name as a link — used where the full ChurchDto would be overkill (e.g. an event card listing its co-hosts). */
public record ChurchRefDto(Long id, String name) {}
