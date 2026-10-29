package com.tenantpulse.core.model;

import java.util.List;

/**
 * A group of changes that are published together under one version: all of them or none.
 * The idempotency key lets the server recognise a retried publish and not create a second version.
 */
public record ChangeSet(String author, String reason, String idempotencyKey, List<SettingChange> changes) {

    public ChangeSet {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
