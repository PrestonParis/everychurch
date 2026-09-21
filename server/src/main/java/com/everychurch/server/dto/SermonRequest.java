package com.everychurch.server.dto;

import java.time.LocalDate;

public record SermonRequest(String title, LocalDate date, String mediaUrl) {}
