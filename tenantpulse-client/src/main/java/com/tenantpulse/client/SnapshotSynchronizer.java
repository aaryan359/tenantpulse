package com.tenantpulse.client;

import com.tenantpulse.core.exception.InvalidSettingValueException;
import com.tenantpulse.core.exception.UnknownSettingException;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.snapshot.ConfigSnapshot;
import com.tenantpulse.core.wire.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

/**
 * Keeps the client's snapshot up to date with the server, using two independent mechanisms so that either one alone
 * is enough:
 * <ul>
 *   <li><b>Push:</b> a live connection announces new versions; the client then fetches the delta itself. Fast
 *       (usually well under a second) but best effort: a message can be lost, duplicated or arrive late.</li>
 *   <li><b>Version check:</b> every couple of seconds the client asks "is there anything newer for me?". This
 *       is what bounds staleness and heals any missed push.</li>
 * </ul>
 * Every announcement is only a hint. Data always comes from "changes since <my version>", and anything not newer than
 * the current version is ignored, so duplicates and late messages (version 41 after 43) change nothing. The snapshot
 * is replaced with a single reference swap, so a reader never sees half of a change set.
 */
final class SnapshotSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(SnapshotSynchronizer.class);
    private static final Duration HEALTHY_CONNECTION = Duration.ofSeconds(10);

    private final AtomicReference<ConfigSnapshot> holder;
    private final ConfigServerApi api;
    private final SnapshotDiskCache cache;
    private final ClientConfig config;
    private final ClientMetrics metrics;
    private final ReconnectBackoff streamBackoff;
    private final ReconnectBackoff pollBackoff;
    private final RandomGenerator random = RandomGenerator.getDefault();
    // A ReentrantLock, not synchronized: the lock is held across a blocking HTTP call, and synchronized would pin
    // the carrier thread when the caller runs on a virtual thread.
    private final ReentrantLock syncLock = new ReentrantLock();

    private volatile boolean running;
    private Thread streamThread;
    private Thread pollThread;

    SnapshotSynchronizer(AtomicReference<ConfigSnapshot> holder, ConfigServerApi api, SnapshotDiskCache cache,
                         ClientConfig config, ClientMetrics metrics) {
        this.holder = holder;
        this.api = api;
        this.cache = cache;
        this.config = config;
        this.metrics = metrics;
        this.streamBackoff = new ReconnectBackoff(config.backoffInitial(), config.backoffMax());
        this.pollBackoff = new ReconnectBackoff(config.backoffInitial(), config.backoffMax());
    }

    void start(Duration initialDelay) {
        running = true;
        // Dedicated daemon threads rather than virtual threads: a library must not borrow the host application's
        // shared virtual-thread carriers for long blocking calls.
        streamThread = Thread.ofPlatform().daemon().name("tenantpulse-stream").start(() -> streamLoop(initialDelay));
        pollThread = Thread.ofPlatform().daemon().name("tenantpulse-poll").start(() -> pollLoop(initialDelay));
    }

    void stop() {
        running = false;
        interrupt(streamThread);
        interrupt(pollThread);
    }

    /** Fetches and installs the full snapshot. Used at start-up and when a delta cannot be applied. */
    void loadFullSnapshot() {
        syncLock.lock();
        try {
            loadFullSnapshotLocked();
        } finally {
            syncLock.unlock();
        }
    }

    // ---------------------------------------------------------------- push

    private void streamLoop(Duration initialDelay) {
        if (!sleep(initialDelay)) {
            return;
        }
        while (running) {
            long started = System.nanoTime();
            Duration serverHint = null;
            try {
                api.stream(this::onPush);
            } catch (ServerException e) {
                serverHint = e.retryAfter();
                log.debug("live connection ended: {}", e.getMessage());
            } catch (RuntimeException e) {
                log.warn("live connection failed: {}", e.getClass().getSimpleName());
            } finally {
                metrics.streamConnected = false;
            }
            if (!running) {
                return;
            }
            metrics.streamReconnects.incrementAndGet();
            if (Duration.ofNanos(System.nanoTime() - started).compareTo(HEALTHY_CONNECTION) > 0) {
                streamBackoff.reset();
            }
            if (!sleep(withHint(streamBackoff.next(), serverHint))) {
                return;
            }
        }
    }

    /** Called for every version the server announces. Duplicates and out-of-order announcements are harmless. */
    void onPush(long version) {
        metrics.pushesReceived.incrementAndGet();
        metrics.streamConnected = true;
        ConfigSnapshot current = holder.get();
        if (current != null && version <= current.version()) {
            metrics.staleMessagesIgnored.incrementAndGet();
            return;
        }
        try {
            syncToLatest();
        } catch (RuntimeException e) {
            // Not fatal: the periodic version check will try again.
            metrics.syncFailures.incrementAndGet();
            log.warn("sync after push failed: {}", describe(e));
        }
    }

    /** A short description of a failure for logs: the type, and our own message for server errors. No data values. */
    private static String describe(RuntimeException e) {
        return e instanceof ServerException ? e.getClass().getSimpleName() + " (" + e.getMessage() + ")"
                : e.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------- periodic check

    private void pollLoop(Duration initialDelay) {
        if (!sleep(initialDelay)) {
            return;
        }
        long interval = config.versionCheckInterval().toMillis();
        while (running) {
            long wait = jittered(interval);
            try {
                checkVersion();
                pollBackoff.reset();
            } catch (ServerException e) {
                metrics.syncFailures.incrementAndGet();
                wait = Math.max(wait, withHint(pollBackoff.next(), e.retryAfter()).toMillis());
                if (e.kind() == ServerException.Kind.REJECTED) {
                    log.error("the server refused our credentials; check the token");
                }
            } catch (RuntimeException e) {
                metrics.syncFailures.incrementAndGet();
                wait = Math.max(wait, pollBackoff.next().toMillis());
                log.warn("version check failed: {}", describe(e));
            }
            if (!sleep(Duration.ofMillis(wait))) {
                return;
            }
        }
    }

    void checkVersion() {
        ConfigSnapshot current = holder.get();
        Wire.VersionResponse version = api.version(current.version());
        metrics.lastSuccessfulSyncMillis = System.currentTimeMillis();
        if (version.latest() < current.version()) {
            log.warn("the server is behind this client (server {}, client {}); keeping the newer snapshot",
                    version.latest(), current.version());
        }
        if (version.relevant() > current.version()) {
            syncToLatest();
        }
    }

    // ---------------------------------------------------------------- applying changes

    /** Fetches everything newer than the current snapshot and installs it with one swap. */
    void syncToLatest() {
        syncLock.lock();
        try {
            ConfigSnapshot base = holder.get();
            Wire.ChangesResponse response = api.changes(base.version());
            if (response.fullSnapshotRequired()) {
                loadFullSnapshotLocked();
                return;
            }
            ConfigSnapshot next = base;
            try {
                for (Wire.ChangeSetView changeSet : response.changeSets()) {
                    if (changeSet.version() <= next.version()) {
                        metrics.staleMessagesIgnored.incrementAndGet();
                        continue;
                    }
                    next = next.apply(changeSet.version(), toChanges(changeSet));
                }
                if (response.toVersion() > next.version()) {
                    next = next.apply(response.toVersion(), List.of());
                }
            } catch (UnknownSettingException | InvalidSettingValueException e) {
                // The server knows settings we do not (a new definition): start over from a full snapshot.
                log.info("delta could not be applied ({}); loading a full snapshot", e.getClass().getSimpleName());
                loadFullSnapshotLocked();
                return;
            }
            install(next, base);
        } finally {
            syncLock.unlock();
        }
    }

    private void loadFullSnapshotLocked() {
        Wire.SnapshotResponse wire = api.snapshot();
        ConfigSnapshot loaded = WireSnapshots.fromWire(wire);
        ConfigSnapshot current = holder.get();
        if (current != null && loaded.version() < current.version()) {
            // Versions only move forward. A server that went back in time must not drag a client back with it.
            metrics.backwardsSnapshotsRejected.incrementAndGet();
            log.error("refusing a snapshot older than the current one (server {}, client {})",
                    loaded.version(), current.version());
            return;
        }
        holder.set(loaded);
        metrics.fullSnapshotsLoaded.incrementAndGet();
        metrics.lastSuccessfulSyncMillis = System.currentTimeMillis();
        persist(loaded);
    }

    private void install(ConfigSnapshot next, ConfigSnapshot base) {
        metrics.lastSuccessfulSyncMillis = System.currentTimeMillis();
        if (next == base) {
            return;
        }
        holder.set(next);
        metrics.syncsApplied.incrementAndGet();
        persist(next);
    }

    private void persist(ConfigSnapshot snapshot) {
        if (cache != null) {
            cache.save(snapshot);
        }
    }

    private static List<SettingChange> toChanges(Wire.ChangeSetView changeSet) {
        List<SettingChange> changes = new ArrayList<>(changeSet.changes().size());
        for (Wire.Change change : changeSet.changes()) {
            changes.add(new SettingChange(change.key(), change.scope(), change.value()));
        }
        return changes;
    }

    // ---------------------------------------------------------------- timing helpers

    private long jittered(long millis) {
        double factor = 0.9 + random.nextDouble() * 0.2;
        return Math.max(1, (long) (millis * factor));
    }

    /** When the server asked for a wait, use it plus a random extra so that clients do not return together. */
    private Duration withHint(Duration backoff, Duration serverHint) {
        if (serverHint == null) {
            return backoff;
        }
        long extra = random.nextLong(Math.max(1, serverHint.toMillis()) + 1);
        Duration asked = serverHint.plusMillis(extra);
        return asked.compareTo(backoff) > 0 ? asked : backoff;
    }

    /** Sleeps; returns false if the client was stopped meanwhile. */
    private boolean sleep(Duration duration) {
        if (!running) {
            return false;
        }
        try {
            if (!duration.isZero() && !duration.isNegative()) {
                Thread.sleep(duration);
            }
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }
}
