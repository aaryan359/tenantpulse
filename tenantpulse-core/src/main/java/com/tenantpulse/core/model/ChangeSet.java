package com.tenantpulse.core.model;

import java.util.List;
import java.util.Objects;

/**
 * A group of changes that are published together under one version: all of them or none.
 * The idempotency key lets the server recognise a retried publish and not create a second version.
 * {@code refVersion} points at the version a rollout promotion or a rollback refers to.
 */
public record ChangeSet(String author, String reason, String idempotencyKey, ChangeKind kind, Long refVersion,
                        List<SettingChange> changes) {

    public ChangeSet {
        kind = kind == null ? ChangeKind.PUBLISH : kind;
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** A plain operator edit. */
    public ChangeSet(String author, String reason, String idempotencyKey, List<SettingChange> changes) {
        this(author, reason, idempotencyKey, ChangeKind.PUBLISH, null, changes);
    }

    public ChangeSet withChanges(List<SettingChange> newChanges) {
        return new ChangeSet(author, reason, idempotencyKey, kind, refVersion, Objects.requireNonNull(newChanges));
    }
}
