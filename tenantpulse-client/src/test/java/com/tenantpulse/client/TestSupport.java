package com.tenantpulse.client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Small helpers shared by the client tests. */
final class TestSupport {

    private TestSupport() {
    }

    static ClientConfig.Builder config() {
        return ClientConfig.builder(URI.create("http://fake.invalid"), "acme", () -> "token")
                .versionCheckInterval(Duration.ofMillis(80))
                .backoff(Duration.ofMillis(10), Duration.ofMillis(100))
                .startupJitter(Duration.ZERO)
                .startupTimeout(Duration.ofSeconds(2));
    }

    static ClientConfig config(Path cacheFile) {
        return config().cacheFile(cacheFile).build();
    }

    /** Waits until the condition holds; fails the test with {@code what} if it does not within the timeout. */
    static void eventually(Duration timeout, String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for: " + what);
            }
        }
        throw new AssertionError("timed out waiting for: " + what);
    }
}
