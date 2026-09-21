package com.everychurch.server.model;

/**
 * Global role on {@code User}. "Visitor" (unauthenticated) is intentionally not a
 * value here — it's the absence of a User, not a persisted state.
 *
 * CHURCH_ADMIN is a capability tier, not a permission set: what a Church Admin can
 * actually do to a given church is resolved through that church's ChurchStaff row
 * (see docs/DATA_MODEL.md), so a Church Admin for one church has no implicit access
 * to another.
 */
public enum Role {
    MEMBER,
    VOLUNTEER,
    CHURCH_ADMIN,
    PLATFORM_ADMIN
}
