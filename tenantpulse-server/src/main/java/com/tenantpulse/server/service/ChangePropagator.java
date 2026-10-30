package com.tenantpulse.server.service;

import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.server.observability.TenantPulseMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Brings the server's in-memory state up to date with the database and tells connected clients.
 *
 * <p>It runs straight after this server publishes a change, and again on a short timer. The timer matters:
 * it picks up changes written by another server instance, so several instances stay in step through the
 * database alone, and it repairs a refresh that failed once.
 */
@Service
public class ChangePropagator {

    private static final Logger log = LoggerFactory.getLogger(ChangePropagator.class);

    private final ConfigStateService state;
    private final ChangeBroadcaster broadcaster;
    private final TenantPulseMetrics metrics;

    public ChangePropagator(ConfigStateService state, ChangeBroadcaster broadcaster, TenantPulseMetrics metrics) {
        this.state = state;
        this.broadcaster = broadcaster;
        this.metrics = metrics;
    }

    public void refreshAndNotify() {
        List<PublishedChangeSet> applied = state.refresh();
        for (PublishedChangeSet published : applied) {
            broadcaster.notifyChange(published.version(), ConfigStateService.affectedTenants(published.changeSet()));
        }
    }

    @Scheduled(fixedDelayString = "${tenantpulse.refresh-interval:1s}")
    void scheduledRefresh() {
        try {
            refreshAndNotify();
        } catch (RuntimeException e) {
            metrics.refreshFailed();
            log.error("refresh from storage failed", e);
        }
    }
}
