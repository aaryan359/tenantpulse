package com.tenantpulse.server.service;

import com.tenantpulse.server.DevTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live push, over real HTTP: connected clients hear about changes that concern their tenant, and only those.
 * (A mock servlet response is not thread-safe, so a push thread writing to it races with the test; a real connection does not.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("memory")
@TestPropertySource(properties = "tenantpulse.security.dev-token-endpoint-enabled=true")
class LivePushTest {

    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*(\\d+)");

    @LocalServerPort
    int port;
    @Autowired
    ChangeBroadcaster broadcaster;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<PushStream> open = new ArrayList<>();

    /** One live connection, read in the background; everything the server sends is collected. */
    private final class PushStream {
        private final StringBuffer received = new StringBuffer();
        private final InputStream body;

        PushStream(String token) throws IOException, InterruptedException {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/stream"))
                    .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            body = response.body();
            Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        received.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // the test closed the connection
                }
            });
            open.add(this);
        }

        boolean heard(long version) {
            return received.toString().contains("data:" + version);
        }

        void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    @AfterEach
    void closeStreams() {
        open.forEach(PushStream::close);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String token(String role, String tenant) throws Exception {
        return DevTokens.fetch(base(), "test-" + role.toLowerCase(), role, tenant);
    }

    /** Publishes one setting at one scope and returns the new version. */
    private long publish(String token, String key, String scope, String value) throws Exception {
        String body = "{\"reason\":\"push test\",\"changes\":[{\"key\":\"" + key + "\",\"scope\":" + scope
                + ",\"value\":\"" + value + "\"}]}";
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/change-sets"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Matcher version = VERSION.matcher(response.body());
        assertTrue(version.find());
        return Long.parseLong(version.group(1));
    }

    private static String clientScope(String tenant) {
        return "{\"level\":\"CLIENT\",\"clientId\":\"" + tenant + "\"}";
    }

    private static void eventually(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    void aChangeIsPushedToItsTenantAndNotToAnother() throws Exception {
        String acme = unique("acme");
        String globex = unique("globex");
        PushStream acmeStream = new PushStream(token("SERVICE", acme));
        PushStream globexStream = new PushStream(token("SERVICE", globex));

        long version = publish(token("OPERATOR", acme), "escort.rule", clientScope(acme), "NONE");

        eventually("acme to be told", () -> acmeStream.heard(version));
        Thread.sleep(300);
        assertFalse(globexStream.heard(version), "globex was told about acme's change");
    }

    @Test
    void aPlatformWideChangeIsPushedToEveryone() throws Exception {
        PushStream one = new PushStream(token("SERVICE", unique("a")));
        PushStream two = new PushStream(token("SERVICE", unique("b")));

        long version = publish(token("ADMIN", "any"), "escort.rule", "{\"level\":\"GLOBAL\"}", "REQUIRED");

        eventually("both streams to be told", () -> one.heard(version) && two.heard(version));
    }

    @Test
    void aNewConnectionIsToldTheCurrentVersionAtOnce() throws Exception {
        String acme = unique("acme");
        long version = publish(token("OPERATOR", acme), "escort.rule", clientScope(acme), "NONE");

        PushStream stream = new PushStream(token("SERVICE", acme));

        eventually("the initial version", () -> stream.heard(version));
    }

    @Test
    void streamsRequireAToken() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/stream")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void theNotificationQueueNeverGrowsBeyondOnePerConnection() throws Exception {
        PushStream stream = new PushStream(token("SERVICE", unique("acme")));
        int connections = broadcaster.connectionCount();

        for (long v = 1; v <= 10_000; v++) {
            broadcaster.notifyChange(1_000_000 + v, null);
        }

        assertTrue(broadcaster.queueDepth() <= connections,
                "at most one waiting notification per connection, got " + broadcaster.queueDepth());
        eventually("the newest version to arrive", () -> stream.heard(1_010_000));
    }
}
