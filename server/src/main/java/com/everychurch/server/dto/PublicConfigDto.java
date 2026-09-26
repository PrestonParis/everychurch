package com.everychurch.server.dto;

/**
 * Config values the client is allowed to see. Deliberately narrow — this is not a
 * general settings dump, just the handful of values a browser legitimately needs
 * (see PublicConfigController). googleMapsEmbedKey is null (not "") when unset, so
 * the client can tell "no key configured" apart from "empty string key" and hide
 * the map rather than render a broken embed.
 */
public record PublicConfigDto(String googleMapsEmbedKey) {}
