package com.everychurch.server.dto;

import java.time.LocalDate;

public record SermonDto(Long id, Long churchId, String title, LocalDate date, String mediaUrl) {}
