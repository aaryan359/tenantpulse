package com.tenantpulse.server.security;

/**
 * The authenticated party behind a request, taken from a verified token. {@code subject} is an opaque id,
 * never a name or an email. {@code tenantId} is null only for ADMIN.
 */
public record Caller(String subject, Role role, String tenantId) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
