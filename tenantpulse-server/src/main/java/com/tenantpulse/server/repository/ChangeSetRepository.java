package com.tenantpulse.server.repository;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;

import java.util.List;

/**
 * Where published change sets are stored. The service talks only to this interface, so the
 * in-memory version can be replaced by Postgres without touching the service.
 */
public interface ChangeSetRepository {

    /**
     * Stores the change set under the next version number and returns it.
     *
     * <p>If a change set with the same idempotency key was already published, nothing new is
     * stored and the earlier result is returned. The check and the store happen as one step, so
     * two identical requests arriving together still produce a single version.
     */
    PublishedChangeSet publish(ChangeSet changeSet);

    /** All change sets with a version greater than {@code version}, oldest first. */
    List<PublishedChangeSet> findSince(long version);

    /** The newest version, or 0 if nothing has been published yet. */
    long latestVersion();
}
