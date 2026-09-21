package com.everychurch.server.dto;

import com.everychurch.server.model.ChurchPermission;

import java.util.Set;

public record ChurchStaffDto(
    Long id,
    Long churchId,
    String userEmail,
    boolean isOwner,
    Set<ChurchPermission> permissions
) {}
