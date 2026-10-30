package com.tenantpulse.server.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * The counters an on-call engineer needs: how far clients lag, what is being rejected or retried,
 * how deep the push queues are, and whether the server is applying changes. Exposed at
 * {@code /actuator/prometheus}. No metric carries a tenant or user name, so cardinality stays small
 * and no identifier can leak.
 */
@Component
public class TenantPulseMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger sseConnections = new AtomicInteger();
    private final AtomicLong configVersion = new AtomicLong();
    private final DistributionSummary clientLag;

    public TenantPulseMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("tenantpulse.sse.connections", sseConnections);
        registry.gauge("tenantpulse.config.version", configVersion);
        this.clientLag = DistributionSummary.builder("tenantpulse.client.lag.versions")
                .description("Versions a client was behind when it asked for changes")
                .publishPercentiles(0.5, 0.99)
                .register(registry);
    }

    /** Registers the gauge for notifications waiting to be sent to slow clients. */
    public void trackQueueDepth(IntSupplier depth) {
        Gauge.builder("tenantpulse.sse.queue.depth", depth::getAsInt)
                .description("Push connections with a notification waiting to be sent")
                .strongReference(true)
                .register(registry);
    }

    public void published(String kind, boolean duplicate) {
        count("tenantpulse.publish", "result", duplicate ? "duplicate" : "accepted", "kind", kind);
    }

    public void publishRejected(String reason) {
        count("tenantpulse.publish", "result", "rejected", "reason", reason);
    }

    public void resyncRejected() {
        count("tenantpulse.resync.rejected");
    }

    public void sseRejected() {
        count("tenantpulse.sse.rejected");
    }

    public void sseOpened() {
        sseConnections.incrementAndGet();
        count("tenantpulse.sse.opened");
    }

    public void sseClosed() {
        sseConnections.decrementAndGet();
    }

    public void sseNotified() {
        count("tenantpulse.sse.notifications");
    }

    public void sseSendFailed() {
        count("tenantpulse.sse.send.failed");
    }

    public void snapshotServed() {
        count("tenantpulse.read", "type", "snapshot");
    }

    public void changesServed(boolean fullSnapshotRequired) {
        count("tenantpulse.read", "type", fullSnapshotRequired ? "changes_full_required" : "changes");
    }

    public void clientLag(long versionsBehind) {
        clientLag.record(Math.max(0, versionsBehind));
    }

    public void refreshFailed() {
        count("tenantpulse.refresh.failed");
    }

    public void error(String type) {
        count("tenantpulse.errors", "type", type);
    }

    public void currentVersion(long version) {
        configVersion.set(version);
    }

    private void count(String name, String... tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }
}
