package com.tenantpulse.client;

import com.tenantpulse.core.wire.Wire;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/** Talks to the config server over HTTP using only the JDK's HTTP client. */
public final class HttpConfigServerApi implements ConfigServerApi {

    private final HttpClient http;
    private final ClientConfig config;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tenantpulse-stream-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    public HttpConfigServerApi(ClientConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public Wire.SnapshotResponse snapshot() {
        return get("/v1/snapshot", Wire.SnapshotResponse.class, null);
    }

    @Override
    public Wire.ChangesResponse changes(long since) {
        return get("/v1/changes?since=" + since, Wire.ChangesResponse.class, null);
    }

    @Override
    public Wire.VersionResponse version(long clientVersion) {
        return get("/v1/version", Wire.VersionResponse.class, clientVersion);
    }

    @Override
    public void stream(LongConsumer onVersion) {
        HttpRequest request = request("/v1/stream", null)
                .header("Accept", "text/event-stream")
                .build();
        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new ServerException(ServerException.Kind.UNAVAILABLE, "could not open the live connection");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerException(ServerException.Kind.UNAVAILABLE, "interrupted while connecting");
        }
        failOnBadStatus(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null));

        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        long idleLimit = config.streamIdleTimeout().toNanos();
        try (Stream<String> lines = response.body()) {
            // The server sends a keepalive every few seconds. If nothing arrives for too long the connection is
            // half-dead (a network cut without a close); closing the stream makes this method return so we reconnect.
            long checkEveryMillis = Math.max(50, TimeUnit.NANOSECONDS.toMillis(idleLimit) / 3);
            ScheduledFuture<?> guard = watchdog.scheduleAtFixedRate(() -> {
                if (System.nanoTime() - lastActivity.get() > idleLimit) {
                    lines.close();
                }
            }, checkEveryMillis, checkEveryMillis, TimeUnit.MILLISECONDS);
            try {
                lines.forEach(line -> {
                    lastActivity.set(System.nanoTime());
                    if (line.startsWith("data:")) {
                        try {
                            onVersion.accept(Long.parseLong(line.substring(5).trim()));
                        } catch (NumberFormatException ignored) {
                            // not a version we understand; the periodic check covers anything we skip
                        }
                    }
                });
            } finally {
                guard.cancel(false);
            }
        } catch (UncheckedIOException e) {
            throw new ServerException(ServerException.Kind.UNAVAILABLE, "live connection broke");
        }
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        http.shutdownNow();
    }

    private <T> T get(String path, Class<T> type, Long clientVersion) {
        HttpRequest request = request(path, clientVersion).timeout(config.requestTimeout()).build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ServerException(ServerException.Kind.UNAVAILABLE,
                    "could not reach the config server: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerException(ServerException.Kind.UNAVAILABLE, "interrupted while calling the server");
        }
        failOnBadStatus(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null));
        try {
            return Json.MAPPER.readValue(response.body(), type);
        } catch (IOException e) {
            throw new ServerException(ServerException.Kind.UNAVAILABLE,
                    "the server sent an unreadable response: " + e.getClass().getSimpleName());
        }
    }

    private HttpRequest.Builder request(String path, Long clientVersion) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                        config.serverUrl().toString().replaceAll("/+$", "") + path))
                .header("Authorization", "Bearer " + config.tokenSupplier().get())
                .GET();
        if (clientVersion != null) {
            builder.header("X-Client-Version", Long.toString(clientVersion));
        }
        return builder;
    }

    private static void failOnBadStatus(int status, String retryAfterHeader) {
        if (status == 200) {
            return;
        }
        if (status == 429 || status == 503) {
            throw new ServerException(ServerException.Kind.BUSY, "the server is busy (HTTP " + status + ")",
                    parseSeconds(retryAfterHeader));
        }
        if (status == 401 || status == 403) {
            throw new ServerException(ServerException.Kind.REJECTED,
                    "the server refused the credentials (HTTP " + status + ")");
        }
        if (status >= 500) {
            throw new ServerException(ServerException.Kind.UNAVAILABLE, "the server failed (HTTP " + status + ")");
        }
        throw new ServerException(ServerException.Kind.REJECTED, "the server rejected the request (HTTP " + status + ")");
    }

    private static Duration parseSeconds(String header) {
        try {
            return header == null ? null : Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
