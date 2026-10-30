package com.tenantpulse.core.snapshot;

import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeContext;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.type.NumberSettingType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hard rule: "No client may ever see the new start with the old end."
 *
 * <p>A writer publishes thousands of change sets that each move night.start and night.end together while keeping
 * end == start + GAP. Readers hammer the shared reference the whole time. A reader that ever observes
 * end != start + GAP has seen half of a change set.
 */
class ConfigSnapshotConcurrencyTest {

    private static final String START = "night.start.minute";
    private static final String END = "night.end.minute";
    private static final long GAP = 1_000_000L;
    private static final int CHANGE_SETS = 5_000;
    private static final int READERS = 8;
    private static final ScopeContext CTX = ScopeContext.office("acme", "blr");

    @Test
    void readersNeverSeeAHalfAppliedChangeSet() throws Exception {
        NumberSettingType minutes = new NumberSettingType(0, Long.MAX_VALUE / 2);
        ConfigSnapshot initial = ConfigSnapshot.empty(List.of(
                SettingDefinition.of(START, minutes, "0", "night window start"),
                SettingDefinition.of(END, minutes, Long.toString(GAP), "night window end")));
        AtomicReference<ConfigSnapshot> holder = new AtomicReference<>(initial);

        AtomicBoolean writerDone = new AtomicBoolean(false);
        AtomicBoolean mixedStateSeen = new AtomicBoolean(false);
        AtomicBoolean versionWentBackwards = new AtomicBoolean(false);
        AtomicLong reads = new AtomicLong();
        CountDownLatch ready = new CountDownLatch(READERS + 1);
        ExecutorService pool = Executors.newFixedThreadPool(READERS + 1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < READERS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                await(ready);
                long lastVersion = -1;
                long localReads = 0;
                while (!writerDone.get()) {
                    ConfigSnapshot snapshot = holder.get(); // one reference, both reads
                    long start = snapshot.get(START, CTX, Long.class);
                    long end = snapshot.get(END, CTX, Long.class);
                    if (end != start + GAP) {
                        mixedStateSeen.set(true);
                    }
                    if (snapshot.version() < lastVersion) {
                        versionWentBackwards.set(true);
                    }
                    lastVersion = snapshot.version();
                    localReads++;
                }
                reads.addAndGet(localReads);
            }));
        }

        futures.add(pool.submit(() -> {
            ready.countDown();
            await(ready);
            ConfigSnapshot current = initial;
            for (int v = 1; v <= CHANGE_SETS; v++) {
                current = current.apply(v, List.of(
                        SettingChange.set(START, Scope.client("acme"), Long.toString(v)),
                        SettingChange.set(END, Scope.client("acme"), Long.toString(v + GAP))));
                holder.set(current);
            }
            writerDone.set(true);
        }));

        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertTrue(reads.get() > 0, "readers must have run");
        assertFalse(mixedStateSeen.get(), "a reader saw the new start with the old end (or vice versa)");
        assertFalse(versionWentBackwards.get(), "a reader saw the version move backwards");
        assertEquals(CHANGE_SETS, holder.get().version());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
