package com.everychurch.server.dto;

import com.everychurch.server.model.ChurchPermission;

import java.util.Set;

public record UpdateStaffPermissionsRequest(Set<ChurchPermission> permissions) {}
