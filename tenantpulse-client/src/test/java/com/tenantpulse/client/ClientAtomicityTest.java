package com.tenantpulse.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.tenantpulse.client.FakeConfigServer.NIGHT_END;
import static com.tenantpulse.client.FakeConfigServer.NIGHT_GAP;
import static com.tenantpulse.client.FakeConfigServer.NIGHT_START;
import static com.tenantpulse.client.TestSupport.eventually;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hard rule, through the whole client: reading continuously while change sets arrive from the server, a reader never sees
 * the new night start with the old end. Several change sets can arrive in one catch-up; they are still applied as whole
 * units and installed with a single swap.
 */
class ClientAtomicityTest {

    private static final int CHANGE_SETS = 400;
    private static final int READERS = 6;

    @Test
    void noReaderEverSeesAMixedNightWindow() throws Exception {
        FakeConfigServer server = new FakeConfigServer();
        server.publishNightWindow(1);
        try (TenantPulseClient client = TenantPulseClient.create(
                TestSupport.config().versionCheckInterval(Duration.ofMillis(10)).build(), server)) {
            client.start();

            AtomicBoolean mixed = new AtomicBoolean();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong reads = new AtomicLong();
            List<Thread> readers = new ArrayList<>();
            for (int i = 0; i < READERS; i++) {
                readers.add(Thread.ofPlatform().start(() -> {
                    long count = 0;
                    while (!stop.get()) {
                        ConfigView view = client.view();           // one view: both reads agree
                        long start = view.get(NIGHT_START, "blr", null, Long.class);
                        long end = view.get(NIGHT_END, "blr", null, Long.class);
                        if (end != start + NIGHT_GAP) {
                            mixed.set(true);
                        }
                        count++;
                    }
                    reads.addAndGet(count);
                }));
            }

            for (int v = 2; v <= CHANGE_SETS; v++) {
                long version = server.publishNightWindow(v);
                if (v % 3 == 0) {
                    server.push(version);   // some changes are pushed, the rest are found by the version check
                }
                if (v % 50 == 0) {
                    Thread.sleep(5);
                }
            }
            eventually(Duration.ofSeconds(10), "client reaches the last change set",
                    () -> client.version() == server.latest());
            stop.set(true);
            for (Thread reader : readers) {
                reader.join();
            }

            assertTrue(reads.get() > 0);
            assertFalse(mixed.get(), "a reader saw the new start with the old end");
            assertEquals((long) CHANGE_SETS, client.view().get(NIGHT_START, "blr", null, Long.class));
        }
    }
}
