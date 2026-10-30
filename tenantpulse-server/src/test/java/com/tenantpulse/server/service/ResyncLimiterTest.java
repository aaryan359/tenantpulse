package com.tenantpulse.server.service;

import com.tenantpulse.server.config.TenantPulseProperties;
import com.tenantpulse.server.observability.TenantPulseMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reconnect-storm protection: excess resync requests are turned away at once instead of piling onto the database. */
class ResyncLimiterTest {

    private static ResyncLimiter limiter(int maxConcurrent, SimpleMeterRegistry registry) {
        TenantPulseProperties properties = new TenantPulseProperties(
                new TenantPulseProperties.Sse(10, Duration.ofSeconds(15), Duration.ofMinutes(30)),
                new TenantPulseProperties.Changes(500),
                new TenantPulseProperties.Resync(maxConcurrent, 2),
                new TenantPulseProperties.Security("x".repeat(40), false, Duration.ofHours(1)),
                Duration.ofSeconds(1));
        return new ResyncLimiter(properties, new TenantPulseMetrics(registry));
    }

    @Test
    void turnsAwayRequestsBeyondTheLimitAndAcceptsThemAgainAfterwards() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResyncLimiter limiter = limiter(2, registry);
        CountDownLatch holding = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        for (int i = 0; i < 2; i++) {
            Thread.ofVirtual().start(() -> limiter.run(() -> {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            }));
        }
        holding.await(5, TimeUnit.SECONDS);

        TooManyResyncsException busy = assertThrows(TooManyResyncsException.class, () -> limiter.run(() -> "x"));
        assertEquals(2, busy.retryAfterSeconds());
        assertEquals(1.0, registry.get("tenantpulse.resync.rejected").counter().count());

        release.countDown();
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                assertEquals("ok", limiter.run(() -> "ok"));
                return;
            } catch (TooManyResyncsException stillBusy) {
                Thread.sleep(10);
            }
        }
        throw new AssertionError("permits were never released");
    }

    @Test
    void releasesThePermitEvenWhenTheWorkFails() {
        ResyncLimiter limiter = limiter(1, new SimpleMeterRegistry());
        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalStateException.class, () -> limiter.run(() -> {
                throw new IllegalStateException("boom");
            }));
        }
        assertEquals("fine", limiter.run(() -> "fine"));
    }
}
