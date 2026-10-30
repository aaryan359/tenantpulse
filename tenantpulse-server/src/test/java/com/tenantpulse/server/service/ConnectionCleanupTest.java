package com.tenantpulse.server.service;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> tokenResponse = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/dev/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"subject\":\"svc\",\"role\":\"SERVICE\",\"tenant\":\"cleanup\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        Matcher token = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(tokenResponse.body());
        assertTrue(token.find());

        int before = broadcaster.connectionCount();
        HttpResponse<InputStream> stream = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/stream"))
                .header("Authorization", "Bearer " + token.group(1)).build(), HttpResponse.BodyHandlers.ofInputStream());
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
