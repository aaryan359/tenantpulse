package com.tenantpulse.server.repository;

import com.tenantpulse.core.model.ChangeKind;
import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps published change sets in memory. Used by the {@code memory} profile for fast tests; the real
 * deployment uses {@link JdbcChangeSetRepository}.
 *
 * <p>Every method is synchronized, so version numbers are handed out one at a time, in order, with no
 * gaps, and a duplicate idempotency key is detected in the same step that would have stored it.
 */
@Repository
@Profile("memory")
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
    public synchronized List<PublishedChangeSet> findSince(long version, int limit) {
        int from = (int) Math.max(0, Math.min(version, published.size()));
        int to = (int) Math.min(published.size(), (long) from + limit);
        return List.copyOf(published.subList(from, to));
    }

    @Override
    public synchronized Optional<PublishedChangeSet> findByVersion(long version) {
        if (version < 1 || version > published.size()) {
            return Optional.empty();
        }
        return Optional.of(published.get((int) version - 1));
    }

    @Override
    public synchronized Optional<PublishedChangeSet> findPromotionOf(long stageVersion) {
        return published.stream()
                .filter(p -> p.changeSet().kind() == ChangeKind.ROLLOUT_PROMOTE
                        && Long.valueOf(stageVersion).equals(p.changeSet().refVersion()))
                .findFirst();
    }

    @Override
    public synchronized long latestVersion() {
        return published.size();
    }
}
