package com.tenantpulse.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A push connection that goes silent without closing (a network cut, a dead proxy) must not leave the client waiting
 * forever: the idle watchdog closes it so the client can reconnect. Uses a real HTTP server that sends one event and
 * then says nothing.
 */
class StreamWatchdogTest {

    @Test
    void aSilentConnectionIsClosedAndTheClientReconnects() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch testOver = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/stream", exchange -> {
            connections.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("event:version\ndata:7\n\n".getBytes());
                out.flush();
                testOver.await(30, java.util.concurrent.TimeUnit.SECONDS);   // then silence: no keepalive, no close
            } catch (IOException | InterruptedException ignored) {
                // the client gave up on us, as intended
            }
        });
        server.start();
        try {
            ClientConfig config = ClientConfig.builder(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "acme", () -> "token")
                    .streamIdleTimeout(Duration.ofMillis(600))
                    .requestTimeout(Duration.ofSeconds(2)).build();
            HttpConfigServerApi api = new HttpConfigServerApi(config);
            AtomicLong lastVersion = new AtomicLong();
            long started = System.nanoTime();

            // The watchdog closes the silent stream, which ends the call; the synchronizer treats that as
            // "connection lost" and reconnects with backoff.
            ServerException ended = assertThrows(ServerException.class, () -> api.stream(lastVersion::set));

            long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
            assertEquals(ServerException.Kind.UNAVAILABLE, ended.kind());
            assertEquals(7L, lastVersion.get(), "the event before the silence was delivered");
            assertTrue(seconds < 10, "the stream should have been closed soon after going silent, took " + seconds + " s");
            assertEquals(1, connections.get());
            api.close();
        } finally {
            testOver.countDown();
            server.stop(0);
        }
    }
}
