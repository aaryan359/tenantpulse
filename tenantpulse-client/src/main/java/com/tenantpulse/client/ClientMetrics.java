package com.tenantpulse.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters a service can export to its own monitoring: how often the client synced, retried, ignored a stale
 * message, or fell back to its disk copy. Plain atomics, so there is no dependency on any metrics library.
 */
public final class ClientMetrics {

    final AtomicLong pushesReceived = new AtomicLong();
    final AtomicLong staleMessagesIgnored = new AtomicLong();
    final AtomicLong syncsApplied = new AtomicLong();
    final AtomicLong syncFailures = new AtomicLong();
    final AtomicLong fullSnapshotsLoaded = new AtomicLong();
    final AtomicLong streamReconnects = new AtomicLong();
    final AtomicLong backwardsSnapshotsRejected = new AtomicLong();
    final AtomicLong diskCacheLoads = new AtomicLong();
    final AtomicLong diskCacheCorrupt = new AtomicLong();
    final AtomicLong diskCacheSaveFailures = new AtomicLong();
    volatile long lastSuccessfulSyncMillis;
    volatile boolean streamConnected;

    public long pushesReceived() {
        return pushesReceived.get();
    }

    /** Messages and snapshots that were older than what the client already had, and were ignored. */
    public long staleMessagesIgnored() {
        return staleMessagesIgnored.get();
    }

    public long syncsApplied() {
        return syncsApplied.get();
    }

    public long syncFailures() {
        return syncFailures.get();
    }

    public long fullSnapshotsLoaded() {
        return fullSnapshotsLoaded.get();
    }

    public long streamReconnects() {
        return streamReconnects.get();
    }

    public long backwardsSnapshotsRejected() {
        return backwardsSnapshotsRejected.get();
    }

    public long diskCacheLoads() {
        return diskCacheLoads.get();
    }

    public long diskCacheCorrupt() {
        return diskCacheCorrupt.get();
    }

    public boolean streamConnected() {
        return streamConnected;
    }

    /** Milliseconds since the last successful contact with the server, or -1 if there has been none yet. */
    public long millisSinceLastSuccessfulSync() {
        long last = lastSuccessfulSyncMillis;
        return last == 0 ? -1 : System.currentTimeMillis() - last;
    }

    /** All counters by name, for logging or exporting. */
    public Map<String, Long> asMap() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("pushesReceived", pushesReceived());
        values.put("staleMessagesIgnored", staleMessagesIgnored());
        values.put("syncsApplied", syncsApplied());
        values.put("syncFailures", syncFailures());
        values.put("fullSnapshotsLoaded", fullSnapshotsLoaded());
        values.put("streamReconnects", streamReconnects());
        values.put("backwardsSnapshotsRejected", backwardsSnapshotsRejected());
        values.put("diskCacheLoads", diskCacheLoads());
        values.put("diskCacheCorrupt", diskCacheCorrupt());
        values.put("diskCacheSaveFailures", diskCacheSaveFailures.get());
        values.put("millisSinceLastSuccessfulSync", millisSinceLastSuccessfulSync());
        return values;
    }
}
