package com.tenantpulse.server.service;

import com.tenantpulse.server.config.TenantPulseProperties;
import com.tenantpulse.server.observability.TenantPulseMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The live-push hub. Each connected client has its own tiny queue of exactly one slot holding the newest
 * version it has not been told about yet. A notification is only a hint ("version N exists"); the client
 * fetches the data itself. Because only the newest value matters, a slow client can never build up a backlog:
 * a newer version simply replaces the older one that was still waiting. Sending runs on separate virtual
 * threads, so one stuck connection cannot delay the others or the publisher.
 */
@Component
public class ChangeBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ChangeBroadcaster.class);

    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
    private final TenantPulseProperties.Sse config;
    private final TenantPulseMetrics metrics;

    public ChangeBroadcaster(TenantPulseProperties properties, TenantPulseMetrics metrics) {
        this.config = properties.sse();
        this.metrics = metrics;
    }

    @PostConstruct
    void registerGauges() {
        metrics.trackQueueDepth(this::queueDepth);
    }

    /**
     * Opens a push stream for {@code tenantId} and immediately tells it the current version, so a client that
     * connects after a missed change learns about it at once.
     *
     * <p>The order matters: the connection is registered first and the current version is read afterwards. A change
     * published before the registration is covered by the version read; a change published after it is delivered by
     * {@link #notifyChange}. Reading the version first would leave a gap in which a change reaches nobody.
     *
     * @param currentVersion reads the newest version relevant to the tenant, called after registration
     * @throws TooManyConnectionsException when the server is at its connection limit
     */
    public SseEmitter subscribe(String tenantId, LongSupplier currentVersion) {
        if (connections.size() >= config.maxConnections()) {
            metrics.sseRejected();
            throw new TooManyConnectionsException();
        }
        SseEmitter emitter = new SseEmitter(config.connectionLifetime().toMillis());
        Connection connection = new Connection(tenantId, emitter);
        connections.add(connection);
        metrics.sseOpened();
        emitter.onCompletion(() -> remove(connection));
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> remove(connection));
        connection.offer(currentVersion.getAsLong());
        return emitter;
    }

    /** Tells the connected clients of the given tenants that {@code version} exists; {@code null} means everyone. */
    public void notifyChange(long version, Set<String> tenants) {
        for (Connection connection : connections) {
            if (tenants == null || tenants.contains(connection.tenantId)) {
                connection.offer(version);
                metrics.sseNotified();
            }
        }
    }

    public int connectionCount() {
        return connections.size();
    }

    /** How many connections currently have a notification waiting to be sent. */
    public int queueDepth() {
        int waiting = 0;
        for (Connection connection : connections) {
            if (connection.pending.get() >= 0) {
                waiting++;
            }
        }
        return waiting;
    }

    /** Keeps idle connections alive through proxies and finds dead ones. */
    @Scheduled(fixedDelayString = "${tenantpulse.sse.heartbeat:15s}")
    void heartbeat() {
        for (Connection connection : connections) {
            sender.execute(connection::heartbeat);
        }
    }

    @PreDestroy
    void shutdown() {
        for (Connection connection : connections) {
            connection.emitter.complete();
        }
        sender.shutdown();
    }

    private void remove(Connection connection) {
        if (connections.remove(connection)) {
            metrics.sseClosed();
        }
    }

    private final class Connection {
        private static final long NOTHING = -1L;

        private final String tenantId;
        private final SseEmitter emitter;
        private final AtomicLong pending = new AtomicLong(NOTHING);
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private final ReentrantLock sendLock = new ReentrantLock();

        private Connection(String tenantId, SseEmitter emitter) {
            this.tenantId = tenantId;
            this.emitter = emitter;
        }

        /** Replaces any waiting version with the newer one and makes sure a sender is running. */
        void offer(long version) {
            pending.accumulateAndGet(version, Math::max);
            schedule();
        }

        private void schedule() {
            if (draining.compareAndSet(false, true)) {
                sender.execute(this::drain);
            }
        }

        private void drain() {
            try {
                long version;
                while ((version = pending.getAndSet(NOTHING)) != NOTHING) {
                    send(SseEmitter.event().name("version").id(Long.toString(version)).data(Long.toString(version)));
                }
            } catch (IOException | RuntimeException e) {
                fail(e);
            } finally {
                draining.set(false);
                if (pending.get() != NOTHING) {
                    schedule();
                }
            }
        }

        void heartbeat() {
            try {
                send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        }

        // A lock rather than synchronized: this is held across a socket write, and synchronized would pin the carrier
        // thread of the virtual thread doing the send.
        private void send(SseEmitter.SseEventBuilder event) throws IOException {
            sendLock.lock();
            try {
                emitter.send(event);
            } finally {
                sendLock.unlock();
            }
        }

        private void fail(Exception e) {
            metrics.sseSendFailed();
            log.debug("push connection closed after send failure: {}", e.getClass().getSimpleName());
            remove(this);
            emitter.completeWithError(e);
        }
    }
}
