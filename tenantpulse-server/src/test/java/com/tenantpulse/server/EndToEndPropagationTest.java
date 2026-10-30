package com.tenantpulse.server;

import com.tenantpulse.client.ClientConfig;
import com.tenantpulse.client.TenantPulseClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The case study's example, for real: the real server over HTTP, the real client library, a change published through the
 * API. The client's version check is set to a minute, so only the live push can deliver the change in time. It must
 * arrive within the 5-second guarantee, for every client, and only for the office that was changed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("memory")
@TestPropertySource(properties = "tenantpulse.security.dev-token-endpoint-enabled=true")
class EndToEndPropagationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    private String base() {
        return "http://localhost:" + port;
    }

    private String token(String subject, String role, String tenant) throws Exception {
        return DevTokens.fetch(base(), subject, role, tenant);
    }

    private int publishCutoff(String operatorToken, String tenant, String office, int minutes) throws Exception {
        String body = "{\"reason\":\"e2e\",\"changes\":[{\"key\":\"booking.cutoff.minutes\",\"scope\":{\"level\":\"OFFICE\","
                + "\"clientId\":\"" + tenant + "\",\"officeId\":\"" + office + "\"},\"value\":\"" + minutes + "\"}]}";
        return HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/v1/change-sets"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + operatorToken)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private TenantPulseClient client(String tenant, String serviceToken) {
        return TenantPulseClient.create(ClientConfig.builder(URI.create(base()), tenant, () -> serviceToken)
                .versionCheckInterval(Duration.ofMinutes(1))     // only push can make this test pass quickly
                .startupTimeout(Duration.ofSeconds(10)).build());
    }

    private static boolean waitFor(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    @Test
    void aChangeToOneOfficeReachesEveryClientWithinFiveSecondsAndOnlyThatOffice() throws Exception {
        String tenant = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        String serviceToken = token("svc", "SERVICE", tenant);
        String operatorToken = token("op", "OPERATOR", tenant);

        List<TenantPulseClient> clients = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                TenantPulseClient client = client(tenant, serviceToken);
                client.start();
                clients.add(client);
            }
            Thread.sleep(800);   // let every live connection open
            for (TenantPulseClient client : clients) {
                assertEquals(60L, client.getLong("booking.cutoff.minutes", "blr"));
            }

            long startedAt = System.nanoTime();
            assertEquals(200, publishCutoff(operatorToken, tenant, "blr", 30));

            for (TenantPulseClient client : clients) {
                assertTrue(waitFor(Duration.ofSeconds(5), () -> client.getLong("booking.cutoff.minutes", "blr") == 30L),
                        "a client did not see the change within 5 seconds; its counters: " + client.metrics().asMap()
                                + ", streamConnected=" + client.metrics().streamConnected());
            }
            long millis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            assertTrue(millis < 5000, "propagation took " + millis + " ms");
            for (TenantPulseClient client : clients) {
                assertEquals(60L, client.getLong("booking.cutoff.minutes", "pune"), "other offices still use 60");
                assertTrue(client.metrics().pushesReceived() > 0, "the change must have arrived by push");
            }
        } finally {
            clients.forEach(TenantPulseClient::close);
        }
    }

    @Test
    void aClientNeverSeesAnotherTenantsChange() throws Exception {
        String acme = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        String globex = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        try (TenantPulseClient globexClient = client(globex, token("svc", "SERVICE", globex))) {
            globexClient.start();
            assertEquals(200, publishCutoff(token("op", "OPERATOR", acme), acme, "blr", 10));
            Thread.sleep(1000);
            assertEquals(60L, globexClient.getLong("booking.cutoff.minutes", "blr"));
        }
    }
}
