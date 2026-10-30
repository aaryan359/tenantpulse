package com.tenantpulse.server.repository;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;

import java.util.List;
import java.util.Optional;

/**
 * Where published change sets are stored. The service talks only to this interface, so the
 * in-memory version (used in tests) and the Postgres version are interchangeable.
 */
public interface ChangeSetRepository {

    /**
     * Stores the change set under the next version number and returns it.
     *
     * <p>If a change set with the same idempotency key was already published, nothing new is
     * stored and the earlier result is returned. The check and the store happen as one step, so
     * two identical requests arriving together still produce a single version.
     *
     * <p>Versions are assigned one at a time and become visible in the order they were assigned,
     * so a reader that has seen version N has seen every version below N.
     */
    PublishedChangeSet publish(ChangeSet changeSet);

    /** Up to {@code limit} change sets with a version greater than {@code version}, oldest first. */
    List<PublishedChangeSet> findSince(long version, int limit);

    Optional<PublishedChangeSet> findByVersion(long version);

    /** The promotion published for the rollout staged at {@code stageVersion}, if there is one. */
    Optional<PublishedChangeSet> findPromotionOf(long stageVersion);

    /** The newest version, or 0 if nothing has been published yet. */
    long latestVersion();
}
