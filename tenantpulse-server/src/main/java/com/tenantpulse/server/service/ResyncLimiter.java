package com.tenantpulse.server.service;

import com.tenantpulse.server.config.TenantPulseProperties;
import com.tenantpulse.server.observability.TenantPulseMetrics;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Caps how many snapshot or delta requests run at once. After a restart hundreds of clients ask together;
 * rather than let them pile up on the database, the excess are turned away at once with "retry shortly" and
 * come back after their own random delay.
 */
@Component
public class ResyncLimiter {

    private final Semaphore permits;
    private final int retryAfterSeconds;
    private final TenantPulseMetrics metrics;

    public ResyncLimiter(TenantPulseProperties properties, TenantPulseMetrics metrics) {
        this.permits = new Semaphore(properties.resync().maxConcurrent());
        this.retryAfterSeconds = properties.resync().retryAfterSeconds();
        this.metrics = metrics;
    }

    public <T> T run(Supplier<T> work) {
        if (!permits.tryAcquire()) {
            metrics.resyncRejected();
            throw new TooManyResyncsException(retryAfterSeconds);
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
