package com.tenantpulse.client;

import com.tenantpulse.core.snapshot.ConfigSnapshot;
import com.tenantpulse.core.wire.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The last good snapshot, saved to disk so a service can start while the server is down.
 *
 * <p><b>Writes are atomic.</b> The data goes to a temporary file in the same folder, is flushed to disk, and is then
 * renamed over the real file. A crash at any point leaves either the old file or the new one, never half of each.
 * <b>Reads are checked.</b> The file carries a SHA-256 checksum of its content and the tenant it belongs to; a damaged
 * file, or one written for another tenant, is ignored rather than trusted.
 */
final class SnapshotDiskCache {

    private static final Logger log = LoggerFactory.getLogger(SnapshotDiskCache.class);

    private record Envelope(String tenant, String checksum, String payload) {
    }

    private final Path file;
    private final String tenantId;
    private final ClientMetrics metrics;

    SnapshotDiskCache(Path file, String tenantId, ClientMetrics metrics) {
        this.file = file.toAbsolutePath();
        this.tenantId = tenantId;
        this.metrics = metrics;
    }

    void save(ConfigSnapshot snapshot) {
        try {
            Files.createDirectories(file.getParent());
            String payload = Json.MAPPER.writeValueAsString(WireSnapshots.toWire(snapshot));
            byte[] bytes = Json.MAPPER.writeValueAsBytes(new Envelope(tenantId, checksum(tenantId, payload), payload));

            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException | RuntimeException e) {
            metrics.diskCacheSaveFailures.incrementAndGet();
            log.warn("could not save the snapshot copy: {}", e.getClass().getSimpleName());
        }
    }

    /** The saved snapshot, or empty if there is none or it cannot be trusted. */
    Optional<ConfigSnapshot> load() {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            metrics.diskCacheCorrupt.incrementAndGet();
            log.warn("could not read the snapshot copy: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        try {
            Envelope envelope = Json.MAPPER.readValue(bytes, Envelope.class);
            if (!tenantId.equals(envelope.tenant())
                    || !checksum(envelope.tenant(), envelope.payload()).equals(envelope.checksum())) {
                throw new IOException("checksum or tenant mismatch");
            }
            Wire.SnapshotResponse wire = Json.MAPPER.readValue(envelope.payload(), Wire.SnapshotResponse.class);
            ConfigSnapshot snapshot = WireSnapshots.fromWire(wire);
            metrics.diskCacheLoads.incrementAndGet();
            return Optional.of(snapshot);
        } catch (IOException | RuntimeException e) {
            metrics.diskCacheCorrupt.incrementAndGet();
            log.warn("ignoring a damaged snapshot copy: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String checksum(String tenant, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(tenant.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
