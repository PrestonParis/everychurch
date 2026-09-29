package com.everychurch.server.model;

/** Who can view a user's public profile (/api/users/{username}) — see PublicProfileService. */
public enum ProfileVisibility {
    PUBLIC,
    MEMBERS_ONLY,
    PRIVATE
}
