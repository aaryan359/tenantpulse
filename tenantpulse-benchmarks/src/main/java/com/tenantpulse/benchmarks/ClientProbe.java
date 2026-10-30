package com.tenantpulse.benchmarks;

import com.tenantpulse.client.ClientConfig;
import com.tenantpulse.client.ConfigUnavailableException;
import com.tenantpulse.client.TenantPulseClient;

import java.net.URI;
import java.nio.file.Path;

/**
 * Stands in for a service that embeds the client: starts it and prints, once a second, what the service would
 * answer. Used to show what happens when the server is killed and restarted.
 *
 * <p>Usage: {@code ClientProbe serverUrl tenant token cacheFile office}.
 */
public final class ClientProbe {

    public static void main(String[] args) throws Exception {
        String url = args[0];
        String tenant = args[1];
        String token = args[2];
        Path cacheFile = Path.of(args[3]);
        String office = args[4];

        TenantPulseClient client = TenantPulseClient.create(ClientConfig.builder(URI.create(url), tenant, () -> token)
                .cacheFile(cacheFile).startupTimeout(java.time.Duration.ofSeconds(5)).build());
        try {
            client.start();
        } catch (ConfigUnavailableException e) {
            System.out.println("START FAILED: " + e.getMessage());
            System.exit(2);
        }
        System.out.println("started (disk copies loaded: " + client.metrics().diskCacheLoads() + ")");
        while (true) {
            System.out.printf("version=%d cutoff[%s]=%d pushConnected=%s secondsSinceServerContact=%d%n",
                    client.version(), office, client.getLong("booking.cutoff.minutes", office),
                    client.metrics().streamConnected(), client.metrics().millisSinceLastSuccessfulSync() / 1000);
            Thread.sleep(1000);
        }
    }
}
