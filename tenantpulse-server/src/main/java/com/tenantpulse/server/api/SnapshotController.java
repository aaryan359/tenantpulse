package com.tenantpulse.server.api;

import com.tenantpulse.core.wire.Wire;
import com.tenantpulse.server.observability.TenantPulseMetrics;
import com.tenantpulse.server.security.Caller;
import com.tenantpulse.server.security.TenantAccessPolicy;
import com.tenantpulse.server.service.ConfigReadService;
import com.tenantpulse.server.service.ResyncLimiter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What client libraries call: the full snapshot to start from, the changes since a version to catch up, and the
 * cheap version check. All three are limited to the caller's own tenant.
 */
@RestController
@RequestMapping("/v1")
public class SnapshotController {

    private final ConfigReadService reads;
    private final TenantAccessPolicy policy;
    private final ResyncLimiter limiter;
    private final TenantPulseMetrics metrics;

    public SnapshotController(ConfigReadService reads, TenantAccessPolicy policy, ResyncLimiter limiter,
                              TenantPulseMetrics metrics) {
        this.reads = reads;
        this.policy = policy;
        this.limiter = limiter;
        this.metrics = metrics;
    }

    @GetMapping("/snapshot")
    public Wire.SnapshotResponse snapshot(Caller caller, @RequestParam(required = false) String tenant) {
        String tenantId = policy.tenantToRead(caller, tenant);
        Wire.SnapshotResponse response = limiter.run(() -> reads.snapshotFor(tenantId));
        metrics.snapshotServed();
        return response;
    }

    @GetMapping("/changes")
    public Wire.ChangesResponse changes(Caller caller, @RequestParam long since,
                                        @RequestParam(required = false) String tenant) {
        String tenantId = policy.tenantToRead(caller, tenant);
        Wire.ChangesResponse response = limiter.run(() -> reads.changesSince(tenantId, since));
        metrics.changesServed(response.fullSnapshotRequired());
        return response;
    }

    /**
     * The check clients run every couple of seconds. A client that reports its own version lets the server track
     * how far behind clients are.
     */
    @GetMapping("/version")
    public Wire.VersionResponse version(Caller caller, @RequestParam(required = false) String tenant,
                                        @RequestHeader(value = "X-Client-Version", required = false) Long clientVersion) {
        Wire.VersionResponse response = reads.version(policy.tenantToRead(caller, tenant));
        if (clientVersion != null) {
            metrics.clientLag(response.relevant() - clientVersion);
        }
        return response;
    }
}
