package com.tenantpulse.client;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter: each wait is a random time between zero and a ceiling that doubles with
 * every failure up to a maximum. The randomness is what stops hundreds of clients that lost the server together
 * from all coming back at the same instant.
 */
public final class ReconnectBackoff {

    private final long initialMillis;
    private final long maxMillis;
    private final RandomGenerator random;
    private int failures;

    public ReconnectBackoff(Duration initial, Duration max) {
        this(initial, max, RandomGenerator.getDefault());
    }

    public ReconnectBackoff(Duration initial, Duration max, RandomGenerator random) {
        this.initialMillis = Math.max(1, initial.toMillis());
        this.maxMillis = Math.max(this.initialMillis, max.toMillis());
        this.random = random;
    }

    /** The wait before the next attempt; each call counts as one more failure. */
    public synchronized Duration next() {
        long ceiling = ceiling(failures);
        failures++;
        return Duration.ofMillis(1 + random.nextLong(ceiling));
    }

    public synchronized void reset() {
        failures = 0;
    }

    /** The largest wait the next call could return. */
    public synchronized long currentCeilingMillis() {
        return ceiling(failures);
    }

    private long ceiling(int attempt) {
        long ceiling = initialMillis;
        for (int i = 0; i < attempt && ceiling < maxMillis; i++) {
            ceiling = Math.min(maxMillis, ceiling * 2);
        }
        return ceiling;
    }
}
