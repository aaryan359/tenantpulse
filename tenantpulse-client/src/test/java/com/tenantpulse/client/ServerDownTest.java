package com.tenantpulse.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static com.tenantpulse.client.FakeConfigServer.CUTOFF;
import static com.tenantpulse.client.TestSupport.eventually;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hard rule: when the server is down, clients keep serving their last good snapshot and can start from the copy saved on
 * disk; the change made just before the crash is still applied everywhere once the server returns.
 */
class ServerDownTest {

    @Test
    void keepsServingTheLastSnapshotWhileTheServerIsDown(@TempDir Path dir) {
        FakeConfigServer server = new FakeConfigServer();
        server.publishCutoff("blr", 30);
        try (TenantPulseClient client = TenantPulseClient.create(TestSupport.config(dir.resolve("c.cache")), server)) {
            client.start();
            server.down = true;

            for (int i = 0; i < 50; i++) {
                assertEquals(30L, client.getLong(CUTOFF, "blr"));
                assertEquals(60L, client.getLong(CUTOFF, "pune"));
            }
            eventually(Duration.ofSeconds(2), "failures are counted", () -> client.metrics().syncFailures() > 0);
            assertEquals(30L, client.getLong(CUTOFF, "blr"));
        }
    }

    @Test
    void startsFromTheDiskCopyWhileTheServerIsStillDown(@TempDir Path dir) {
        Path cache = dir.resolve("c.cache");
        FakeConfigServer server = new FakeConfigServer();
        server.publishCutoff("blr", 30);
        try (TenantPulseClient first = TenantPulseClient.create(TestSupport.config(cache), server)) {
            first.start();
        }

        server.down = true;   // the server is gone when this service restarts
        try (TenantPulseClient restarted = TenantPulseClient.create(TestSupport.config(cache), server)) {
            restarted.start();
            assertEquals(30L, restarted.getLong(CUTOFF, "blr"));
            assertEquals(1L, restarted.metrics().diskCacheLoads());
        }
    }

    @Test
    void theChangeMadeJustBeforeTheCrashReachesEveryoneWhenTheServerReturns(@TempDir Path dir) {
        FakeConfigServer server = new FakeConfigServer();
        server.publishCutoff("blr", 60);
        try (TenantPulseClient connected = TenantPulseClient.create(TestSupport.config(dir.resolve("a.cache")), server);
             TenantPulseClient sleeping = TenantPulseClient.create(TestSupport.config(dir.resolve("b.cache")), server)) {
            connected.start();
            sleeping.start();

            server.publishCutoff("blr", 20);   // the last change before the crash
            server.down = true;                // the server dies before any push can reach anyone

            assertEquals(60L, sleeping.getLong(CUTOFF, "blr"));   // nothing new while it is down
            server.down = false;               // the server returns, the change is still in its log

            eventually(Duration.ofSeconds(3), "both clients apply the change",
                    () -> connected.getLong(CUTOFF, "blr") == 20L && sleeping.getLong(CUTOFF, "blr") == 20L);
        }
    }

    @Test
    void failsClearlyWhenThereIsNeitherAServerNorADiskCopy(@TempDir Path dir) {
        FakeConfigServer server = new FakeConfigServer();
        server.down = true;
        TenantPulseClient client = TenantPulseClient.create(TestSupport.config()
                .cacheFile(dir.resolve("none.cache")).startupTimeout(Duration.ofMillis(300)).build(), server);
        assertThrows(ConfigUnavailableException.class, client::start);
        client.close();
    }

    @Test
    void waitsForAServerThatComesUpDuringStartup() throws Exception {
        FakeConfigServer server = new FakeConfigServer();
        server.publishCutoff("blr", 30);
        server.down = true;
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            server.down = false;
        });
        try (TenantPulseClient client = TenantPulseClient.create(TestSupport.config().build(), server)) {
            client.start();
            assertEquals(30L, client.getLong(CUTOFF, "blr"));
            assertTrue(client.metrics().fullSnapshotsLoaded() >= 1);
        }
    }

    @Test
    void readingBeforeStartIsAnError() {
        TenantPulseClient client = TenantPulseClient.create(TestSupport.config().build(), new FakeConfigServer());
        assertThrows(IllegalStateException.class, () -> client.getLong(CUTOFF, "blr"));
    }
}
