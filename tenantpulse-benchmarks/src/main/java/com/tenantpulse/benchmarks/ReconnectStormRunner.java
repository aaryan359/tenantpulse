package com.tenantpulse.benchmarks;

import com.tenantpulse.client.ClientConfig;
import com.tenantpulse.client.TenantPulseClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts N clients (default 500) at the same instant with nothing cached, as after a server restart, and reports how
 * long it took for all of them to get a snapshot and how many requests the server turned away with "busy, retry
 * shortly". The point is that the server stays up and every client still gets through.
 *
 * <p>Usage: {@code ReconnectStormRunner [serverUrl] [clients]}.
 */
public final class ReconnectStormRunner {

    private static final Pattern REJECTED = Pattern.compile("tenantpulse_resync_rejected_total\\{[^}]*}\\s+([0-9.]+)");

    public static void main(String[] args) throws Exception {
        String url = Bench.arg(args, 0, "http://localhost:8080");
        int clientCount = Integer.parseInt(Bench.arg(args, 1, "500"));
        String tenant = "storm-" + (System.currentTimeMillis() % 100_000);
        String serviceToken = Bench.token(url, "storm-service", "SERVICE", tenant);
        String operatorToken = Bench.token(url, "storm-operator", "OPERATOR", tenant);
        for (int i = 0; i < 20; i++) {
            Bench.publishOffice(url, operatorToken, tenant, "office-" + i, "booking.cutoff.minutes",
                    Long.toString(10 + i), "storm-" + tenant + "-" + i);
        }

        double rejectedBefore = rejected(url);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<TenantPulseClient> clients = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        long begin = System.nanoTime();
        for (int i = 0; i < clientCount; i++) {
            TenantPulseClient client = TenantPulseClient.create(ClientConfig.builder(URI.create(url), tenant,
                    () -> serviceToken).startupTimeout(Duration.ofSeconds(60)).build());
            clients.add(client);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    client.start();
                    started.incrementAndGet();
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        double seconds = (System.nanoTime() - begin) / 1e9;
        double rejectedAfter = rejected(url);

        long retries = clients.stream().mapToLong(c -> c.metrics().syncFailures()).sum();
        System.out.printf("%d clients started at once: %d got a snapshot, %d failed, all done in %.2f s%n",
                clientCount, started.get(), failed.get(), seconds);
        System.out.printf("server answered 'busy, retry shortly' %.0f times; clients that retried: see below%n",
                rejectedAfter - rejectedBefore);
        System.out.printf("client-side sync failures during the storm: %d%n", retries);
        clients.forEach(TenantPulseClient::close);
        System.exit(failed.get() == 0 ? 0 : 1);
    }

    private static double rejected(String url) {
        try {
            Matcher matcher = REJECTED.matcher(Bench.get(url + "/actuator/prometheus", null));
            return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
