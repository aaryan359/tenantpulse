package com.tenantpulse.client;

import com.tenantpulse.core.resolve.ResolvedSetting;
import com.tenantpulse.core.snapshot.ConfigSnapshot;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * The library a service embeds. Reads are answered from an in-memory snapshot, with no network call and no lock, in
 * microseconds. A background synchronizer keeps the snapshot current (live push plus a periodic version check) and a
 * checksummed copy on disk lets the service start even when the config server is down.
 *
 * <pre>{@code
 * TenantPulseClient client = TenantPulseClient.create(
 *         ClientConfig.builder(URI.create("http://config:8080"), "acme", tokens::current)
 *                 .cacheFile(Path.of("/var/lib/booking/tenantpulse.cache")).build());
 * client.start();
 * long cutoff = client.getLong("booking.cutoff.minutes", "blr");
 * }</pre>
 *
 * <b>Consistency.</b> A published change reaches a connected client within the version-check interval (2 seconds by
 * default) even if the push is lost. A change set is never seen half-applied. A client never moves back to an older
 * version. When several settings must agree, read them from one {@link #view()}.
 */
public final class TenantPulseClient implements AutoCloseable {

    private final ClientConfig config;
    private final ConfigServerApi api;
    private final ClientMetrics metrics = new ClientMetrics();
    private final AtomicReference<ConfigSnapshot> holder = new AtomicReference<>();
    private final SnapshotDiskCache cache;
    private final SnapshotSynchronizer synchronizer;
    private final AtomicBoolean starting = new AtomicBoolean();
    private volatile boolean started;

    private TenantPulseClient(ClientConfig config, ConfigServerApi api) {
        this.config = config;
        this.api = api;
        this.cache = config.cacheFile() == null ? null
                : new SnapshotDiskCache(config.cacheFile(), config.tenantId(), metrics);
        this.synchronizer = new SnapshotSynchronizer(holder, api, cache, config, metrics);
    }

    public static TenantPulseClient create(ClientConfig config) {
        return new TenantPulseClient(config, new HttpConfigServerApi(config));
    }

    /** For tests: use a stand-in server connection, for example one that fails on demand. */
    public static TenantPulseClient create(ClientConfig config, ConfigServerApi api) {
        return new TenantPulseClient(config, api);
    }

    /**
     * Loads the configuration and starts keeping it current. If a valid disk copy exists the client starts from it at
     * once, even with the server down, and catches up in the background. Otherwise it waits for the server for up to
     * the configured start-up timeout.
     *
     * @throws ConfigUnavailableException if there is no disk copy and the server cannot be reached in time
     */
    public void start() {
        // Not synchronized: start() makes blocking network calls, which must not pin a virtual-thread carrier.
        if (!starting.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        boolean fromDisk = false;
        if (cache != null) {
            var saved = cache.load();
            saved.ifPresent(holder::set);
            fromDisk = saved.isPresent();
        }
        if (!fromDisk) {
            loadFirstSnapshot();
        }
        started = true;
        Duration jitter = Duration.ZERO;
        if (fromDisk && !config.startupJitter().isZero()) {
            // Many services restarting together must not all hit the server in the same instant.
            jitter = Duration.ofMillis(RandomGenerator.getDefault().nextLong(config.startupJitter().toMillis() + 1));
        }
        synchronizer.start(jitter);
    }

    private void loadFirstSnapshot() {
        long deadline = System.nanoTime() + config.startupTimeout().toNanos();
        ReconnectBackoff backoff = new ReconnectBackoff(config.backoffInitial(), config.backoffMax());
        while (true) {
            try {
                synchronizer.loadFullSnapshot();
                if (holder.get() != null) {
                    return;
                }
            } catch (ServerException e) {
                if (e.kind() == ServerException.Kind.REJECTED) {
                    throw new ConfigUnavailableException("the server refused the credentials and there is no disk copy");
                }
            }
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMillis <= 0) {
                throw new ConfigUnavailableException(
                        "the config server is unreachable and there is no disk copy to start from");
            }
            try {
                Thread.sleep(Math.min(backoff.next().toMillis(), remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConfigUnavailableException("interrupted while waiting for the config server");
            }
        }
    }

    /** A fixed view of the configuration right now. Reads within one view always agree with each other. */
    public ConfigView view() {
        ConfigSnapshot snapshot = holder.get();
        if (snapshot == null) {
            throw new IllegalStateException("client has not been started");
        }
        return new ConfigView(snapshot, config.tenantId());
    }

    public <T> T get(String key, String officeId, String shiftId, Class<T> type) {
        return view().get(key, officeId, shiftId, type);
    }

    public <T> T get(String key, Class<T> type) {
        return view().get(key, type);
    }

    public long getLong(String key, String officeId) {
        return view().getLong(key, officeId);
    }

    public ResolvedSetting explain(String key, String officeId, String shiftId) {
        return view().explain(key, officeId, shiftId);
    }

    /** The version of the snapshot currently served. */
    public long version() {
        return view().version();
    }

    public ClientMetrics metrics() {
        return metrics;
    }

    /** Stops the background work. The last snapshot stays readable. */
    @Override
    public void close() {
        synchronizer.stop();
        api.close();
    }
}
