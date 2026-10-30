package com.tenantpulse.client;

import com.tenantpulse.core.snapshot.ConfigSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static com.tenantpulse.client.FakeConfigServer.CUTOFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The disk copy must never be trusted blindly: damaged, truncated or foreign files are ignored. */
class DiskCacheCorruptionTest {

    @TempDir
    Path dir;

    private Path file;
    private ClientMetrics metrics;
    private ConfigSnapshot snapshot;

    @BeforeEach
    void setUp() {
        file = dir.resolve("snapshot.cache");
        metrics = new ClientMetrics();
        FakeConfigServer server = new FakeConfigServer();
        server.publishCutoff("blr", 30);
        snapshot = WireSnapshots.fromWire(server.snapshot());
    }

    private SnapshotDiskCache cacheFor(String tenant) {
        return new SnapshotDiskCache(file, tenant, metrics);
    }

    @Test
    void roundTripsASnapshot() {
        cacheFor("acme").save(snapshot);
        Optional<ConfigSnapshot> loaded = cacheFor("acme").load();
        assertTrue(loaded.isPresent());
        assertEquals(snapshot.version(), loaded.get().version());
        assertEquals(snapshot.overrideEntries(), loaded.get().overrideEntries());
    }

    @Test
    void aMissingFileIsNotAnError() {
        assertTrue(cacheFor("acme").load().isEmpty());
        assertEquals(0L, metrics.diskCacheCorrupt());
    }

    @Test
    void aFlippedByteIsDetected() throws IOException {
        cacheFor("acme").save(snapshot);
        byte[] bytes = Files.readAllBytes(file);
        int at = new String(bytes).indexOf("30");           // change the cut-off value inside the payload
        bytes[at] = (byte) '9';
        Files.write(file, bytes);

        assertTrue(cacheFor("acme").load().isEmpty());
        assertEquals(1L, metrics.diskCacheCorrupt());
    }

    @Test
    void aTruncatedFileIsDetected() throws IOException {
        cacheFor("acme").save(snapshot);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length / 2));

        assertTrue(cacheFor("acme").load().isEmpty());
        assertEquals(1L, metrics.diskCacheCorrupt());
    }

    @Test
    void garbageIsDetected() throws IOException {
        Files.writeString(file, "this is not a snapshot");
        assertTrue(cacheFor("acme").load().isEmpty());
        assertEquals(1L, metrics.diskCacheCorrupt());
    }

    @Test
    void anotherTenantsCopyIsNeverLoaded() {
        cacheFor("acme").save(snapshot);
        assertTrue(cacheFor("globex").load().isEmpty());
    }

    @Test
    void savingLeavesNoTemporaryFilesBehind() throws IOException {
        SnapshotDiskCache cache = cacheFor("acme");
        for (int i = 0; i < 20; i++) {
            cache.save(snapshot);
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1L, files.count(), "only the cache file itself should remain");
        }
    }

    @Test
    void aSaveNeverReplacesAGoodFileWithAPartialOne() throws IOException {
        SnapshotDiskCache cache = cacheFor("acme");
        cache.save(snapshot);
        // Many saves from several threads: every observed state of the file must be a complete, valid copy.
        Thread[] writers = new Thread[4];
        for (int i = 0; i < writers.length; i++) {
            writers[i] = Thread.ofPlatform().start(() -> {
                for (int n = 0; n < 50; n++) {
                    cache.save(snapshot);
                }
            });
        }
        int invalid = 0;
        for (int n = 0; n < 200; n++) {
            if (new SnapshotDiskCache(file, "acme", new ClientMetrics()).load().isEmpty()) {
                invalid++;
            }
        }
        for (Thread writer : writers) {
            try {
                writer.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertEquals(0, invalid, "a reader saw a partially written file");
    }

    @Test
    void aClientWithACorruptCopyFallsBackToTheServer() throws IOException {
        Files.writeString(file, "garbage");
        FakeConfigServer server = new FakeConfigServer();
        server.publishCutoff("blr", 25);
        try (TenantPulseClient client = TenantPulseClient.create(TestSupport.config(file), server)) {
            client.start();
            assertEquals(25L, client.getLong(CUTOFF, "blr"));
            assertEquals(1L, client.metrics().diskCacheCorrupt());
            assertFalse(client.metrics().diskCacheLoads() > 0);
        }
    }
}
