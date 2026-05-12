package com.everychurch.server.dto;

import java.util.List;

public record ChurchDto(
    Long id,
    String name,
    String denomination,
    String address,
    String city,
    String state,
    String zipCode,
    String phone,
    String email,
    String website,
    String description,
    String imageUrl,
    String pastor,
    String facebook,
    String instagram,
    String youtube,
    Boolean claimed,
    List<ServiceTimeDto> serviceTimes
) {}
