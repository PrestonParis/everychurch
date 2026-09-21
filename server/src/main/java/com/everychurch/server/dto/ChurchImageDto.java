package com.everychurch.server.dto;

public record ChurchImageDto(Long id, Long churchId, String url, int sortOrder) {}
