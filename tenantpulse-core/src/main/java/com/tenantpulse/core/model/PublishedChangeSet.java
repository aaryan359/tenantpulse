package com.tenantpulse.core.model;

import java.time.Instant;

/** A change set after it was published: the same content plus the version it was given and when. */
public record PublishedChangeSet(long version, ChangeSet changeSet, Instant publishedAt) {
}
