package com.tenantpulse.core.model;

import java.util.Objects;

/**
 * A place an override can live: the whole platform, one client, one office of a client,
 * or one shift of an office. Ids a level does not use must be null, so every scope has
 * exactly one valid representation.
 */
public record Scope(ScopeLevel level, String clientId, String officeId, String shiftId) {

    private static final Scope GLOBAL = new Scope(ScopeLevel.GLOBAL, null, null, null);

    public Scope {
        Objects.requireNonNull(level, "level");
        switch (level) {
            case GLOBAL -> require(clientId == null && officeId == null && shiftId == null,
                    "GLOBAL scope takes no ids");
            case CLIENT -> require(present(clientId) && officeId == null && shiftId == null,
                    "CLIENT scope takes only clientId");
            case OFFICE -> require(present(clientId) && present(officeId) && shiftId == null,
                    "OFFICE scope takes clientId and officeId");
            case SHIFT -> require(present(clientId) && present(officeId) && present(shiftId),
                    "SHIFT scope takes clientId, officeId and shiftId");
        }
    }

    public static Scope global() {
        return GLOBAL;
    }

    public static Scope client(String clientId) {
        return new Scope(ScopeLevel.CLIENT, clientId, null, null);
    }

    public static Scope office(String clientId, String officeId) {
        return new Scope(ScopeLevel.OFFICE, clientId, officeId, null);
    }

    public static Scope shift(String clientId, String officeId, String shiftId) {
        return new Scope(ScopeLevel.SHIFT, clientId, officeId, shiftId);
    }

    /** Stable text form, e.g. {@code OFFICE:acme/blr-1}. Used for logs and storage keys. */
    public String key() {
        return switch (level) {
            case GLOBAL -> "GLOBAL";
            case CLIENT -> "CLIENT:" + clientId;
            case OFFICE -> "OFFICE:" + clientId + "/" + officeId;
            case SHIFT -> "SHIFT:" + clientId + "/" + officeId + "/" + shiftId;
        };
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
