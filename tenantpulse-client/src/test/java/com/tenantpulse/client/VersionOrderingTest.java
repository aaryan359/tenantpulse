package com.tenantpulse.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.tenantpulse.client.FakeConfigServer.CUTOFF;
import static com.tenantpulse.client.TestSupport.eventually;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hard rule: versions only move forward. A client that has applied version 43 and then hears "41" (late) or "43"
 * (duplicate) must do nothing: no change of value, no step back in version.
 */
class VersionOrderingTest {

    @Test
    void lateAndDuplicateAnnouncementsAreIgnored() throws Exception {
        FakeConfigServer server = new FakeConfigServer();
        for (int v = 1; v <= 43; v++) {
            server.publishCutoff("blr", 100 + v);
        }
        // a long check interval so only pushes drive this test
        try (TenantPulseClient client = TenantPulseClient.create(
                TestSupport.config().versionCheckInterval(Duration.ofMinutes(5)).build(), server)) {
            client.start();
            assertEquals(43L, client.version());

            server.publishCutoff("blr", 7);                  // version 44
            server.push(44);
            eventually(Duration.ofSeconds(2), "client reaches 44", () -> client.version() == 44);

            long staleBefore = client.metrics().staleMessagesIgnored();
            server.push(41);                                  // late message
            server.push(43);                                  // duplicate of an older one
            server.push(44);                                  // duplicate of the current one
            eventually(Duration.ofSeconds(2), "stale messages counted",
                    () -> client.metrics().staleMessagesIgnored() >= staleBefore + 3);

            assertEquals(44L, client.version(), "version must not move backwards");
            assertEquals(7L, client.getLong(CUTOFF, "blr"), "value must not change");
        }
    }

    @Test
    void versionNeverGoesBackwardsWhileMessagesArriveInAnyOrder() throws Exception {
        FakeConfigServer server = new FakeConfigServer();
        server.publishCutoff("blr", 1);
        try (TenantPulseClient client = TenantPulseClient.create(
                TestSupport.config().versionCheckInterval(Duration.ofMillis(30)).build(), server)) {
            client.start();
            AtomicBoolean wentBackwards = new AtomicBoolean();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong last = new AtomicLong();
            Thread watcher = Thread.ofPlatform().start(() -> {
                while (!stop.get()) {
                    long version = client.version();
                    if (version < last.get()) {
                        wentBackwards.set(true);
                    }
                    last.set(version);
                }
            });
            for (int round = 0; round < 50; round++) {
                long version = server.publishCutoff("blr", 10 + round);
                server.push(version);
                server.push(version - 1);   // out of order
                server.push(version);       // duplicate
                server.push(1);             // ancient
            }
            eventually(Duration.ofSeconds(5), "client catches up", () -> client.version() == server.latest());
            stop.set(true);
            watcher.join();
            assertFalse(wentBackwards.get(), "an observer saw the version move backwards");
            assertTrue(client.metrics().staleMessagesIgnored() > 0);
        }
    }

    @Test
    void aServerThatIsBehindTheClientNeverDragsItBack() throws Exception {
        FakeConfigServer server = new FakeConfigServer();
        for (int v = 1; v <= 5; v++) {
            server.publishCutoff("blr", 20 + v);
        }
        try (TenantPulseClient client = TenantPulseClient.create(TestSupport.config().build(), server)) {
            client.start();
            assertEquals(5L, client.version());

            server.rewind(2);   // the server comes back with older data than the client already has
            Thread.sleep(500);  // several version checks pass

            assertEquals(5L, client.version());
            assertEquals(25L, client.getLong(CUTOFF, "blr"));
        }
    }

    @Test
    void anOlderFullSnapshotIsRefusedAndTheCurrentOneKept() {
        FakeConfigServer newer = new FakeConfigServer();
        FakeConfigServer older = new FakeConfigServer();
        for (int v = 1; v <= 5; v++) {
            newer.publishCutoff("blr", 20 + v);
        }
        older.publishCutoff("blr", 99);
        older.publishCutoff("blr", 98);

        var holder = new java.util.concurrent.atomic.AtomicReference<>(WireSnapshots.fromWire(newer.snapshot()));
        ClientMetrics metrics = new ClientMetrics();
        SnapshotSynchronizer synchronizer =
                new SnapshotSynchronizer(holder, older, null, TestSupport.config().build(), metrics);

        synchronizer.loadFullSnapshot();   // the older server hands over its (version 2) snapshot

        assertEquals(1L, metrics.backwardsSnapshotsRejected());
        assertEquals(5L, holder.get().version());
        assertEquals(25L, holder.get().get(CUTOFF, com.tenantpulse.core.model.ScopeContext.office("acme", "blr"),
                Long.class));
    }
}
