package com.everychurch.server.dto;

import com.everychurch.server.model.ProfileVisibility;

public record UpdatePrivacyRequest(
    ProfileVisibility profileVisibility,
    boolean showLocation,
    boolean showTradition,
    boolean showHomeChurch,
    boolean showFollowing
) {}
