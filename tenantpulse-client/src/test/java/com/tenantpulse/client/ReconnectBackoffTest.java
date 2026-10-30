package com.tenantpulse.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectBackoffTest {

    @Test
    void ceilingDoublesUntilTheMaximum() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofMillis(500), Duration.ofSeconds(30));
        long[] expected = {500, 1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000};
        for (long ceiling : expected) {
            assertEquals(ceiling, backoff.currentCeilingMillis());
            backoff.next();
        }
    }

    @Test
    void everyWaitIsWithinTheCeiling() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofMillis(100), Duration.ofSeconds(5));
        for (int i = 0; i < 200; i++) {
            long ceiling = backoff.currentCeilingMillis();
            long wait = backoff.next().toMillis();
            assertTrue(wait >= 1 && wait <= ceiling, "wait " + wait + " outside 1.." + ceiling);
        }
    }

    @Test
    void waitsAreSpreadOutSoClientsDoNotReturnTogether() {
        Set<Long> distinct = new HashSet<>();
        for (int client = 0; client < 500; client++) {
            ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofSeconds(1), Duration.ofSeconds(30));
            distinct.add(backoff.next().toMillis());
        }
        assertTrue(distinct.size() > 200, "500 clients should pick many different delays, got " + distinct.size());
    }

    @Test
    void resetStartsOver() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofMillis(500), Duration.ofSeconds(30));
        backoff.next();
        backoff.next();
        backoff.reset();
        assertEquals(500L, backoff.currentCeilingMillis());
    }

    @Test
    void usesTheGivenRandomSource() {
        RandomGenerator alwaysTop = new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public long nextLong(long bound) {
                return bound - 1;
            }
        };
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofMillis(100), Duration.ofSeconds(1), alwaysTop);
        assertEquals(100L, backoff.next().toMillis());
        assertEquals(200L, backoff.next().toMillis());
    }
}
