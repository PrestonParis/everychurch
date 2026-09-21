package com.everychurch.server.dto;

import com.everychurch.server.model.ChurchPermission;

import java.util.Set;

public record AddStaffRequest(String email, Set<ChurchPermission> permissions) {}
