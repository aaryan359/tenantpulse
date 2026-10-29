package com.tenantpulse.server.repository;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps published change sets in memory. Everything is lost on restart; Postgres replaces this later.
 *
 * <p>Every method is synchronized, so version numbers are handed out one at a time, in order, with no
 * gaps, and a duplicate idempotency key is detected in the same step that would have stored it.
 */
@Repository
public class InMemoryChangeSetRepository implements ChangeSetRepository {

    private final List<PublishedChangeSet> published = new ArrayList<>();
    private final Map<String, PublishedChangeSet> byIdempotencyKey = new HashMap<>();

    @Override
    public synchronized PublishedChangeSet publish(ChangeSet changeSet) {
        String key = changeSet.idempotencyKey();
        if (key != null) {
            PublishedChangeSet earlier = byIdempotencyKey.get(key);
            if (earlier != null) {
                return earlier;
            }
        }
        long version = published.size() + 1L;
        PublishedChangeSet entry = new PublishedChangeSet(version, changeSet, Instant.now());
        published.add(entry);
        if (key != null) {
            byIdempotencyKey.put(key, entry);
        }
        return entry;
    }

    @Override
    public synchronized List<PublishedChangeSet> findSince(long version) {
        int from = (int) Math.max(0, Math.min(version, published.size()));
        return List.copyOf(published.subList(from, published.size()));
    }

    @Override
    public synchronized long latestVersion() {
        return published.size();
    }
}
