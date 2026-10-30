package com.tenantpulse.server.service;

import com.tenantpulse.server.DevTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A client that vanishes must not leak its push connection: the heartbeat finds it and it is removed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("memory")
@TestPropertySource(properties = {
        "tenantpulse.security.dev-token-endpoint-enabled=true",
        "tenantpulse.sse.heartbeat=300ms"})
class ConnectionCleanupTest {

    @LocalServerPort
    int port;
    @Autowired
    ChangeBroadcaster broadcaster;

    @Test
    void aDroppedClientsConnectionIsRemovedByTheHeartbeat() throws Exception {
        String base = "http://localhost:" + port;
        String token = DevTokens.fetch(base, "svc", "SERVICE", "cleanup");

        int before = broadcaster.connectionCount();
        HttpResponse<InputStream> stream = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/v1/stream")).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        waitUntil(() -> broadcaster.connectionCount() == before + 1, "the connection to be registered");

        stream.body().close();                     // the client goes away without a word

        waitUntil(() -> broadcaster.connectionCount() == before, "the dead connection to be removed");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
