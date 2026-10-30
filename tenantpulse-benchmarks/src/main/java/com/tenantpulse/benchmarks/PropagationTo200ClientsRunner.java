package com.tenantpulse.benchmarks;

import com.tenantpulse.client.ClientConfig;
import com.tenantpulse.client.TenantPulseClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Measures how long a published change takes to become visible in every one of N connected clients (default 200).
 * For each round it publishes a new value for one office, then times how long each client takes to return it. The
 * clock starts just before the publish request is sent, so the figures include the database commit.
 *
 * <p>Usage: {@code PropagationTo200ClientsRunner [serverUrl] [clients] [rounds]}. The server must run with
 * {@code tenantpulse.security.dev-token-endpoint-enabled=true}.
 */
public final class PropagationTo200ClientsRunner {

    private static final String KEY = "booking.cutoff.minutes";
    private static final String OFFICE = "blr";

    public static void main(String[] args) throws Exception {
        String url = Bench.arg(args, 0, "http://localhost:8080");
        int clientCount = Integer.parseInt(Bench.arg(args, 1, "200"));
        int rounds = Integer.parseInt(Bench.arg(args, 2, "10"));
        String tenant = "bench-" + (System.currentTimeMillis() % 100_000);
        String serviceToken = Bench.token(url, "bench-service", "SERVICE", tenant);
        String operatorToken = Bench.token(url, "bench-operator", "OPERATOR", tenant);

        System.out.printf("starting %d clients against %s (tenant %s)%n", clientCount, url, tenant);
        List<TenantPulseClient> clients = new ArrayList<>();
        List<Thread> starters = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            TenantPulseClient client = TenantPulseClient.create(ClientConfig.builder(URI.create(url), tenant,
                    () -> serviceToken).startupTimeout(Duration.ofSeconds(30)).build());
            clients.add(client);
            starters.add(Thread.ofVirtual().start(client::start));
        }
        for (Thread starter : starters) {
            starter.join();
        }
        Thread.sleep(1500); // let every live connection settle
        System.out.printf("all %d clients started%n%n", clientCount);

        long[] all = new long[clientCount * rounds];
        int filled = 0;
        System.out.printf("%-7s %-10s %-10s %-10s%n", "round", "p50 (ms)", "p99 (ms)", "max (ms)");
        for (int round = 1; round <= rounds; round++) {
            long expected = 100 + round;
            AtomicLongArray seenAt = new AtomicLongArray(clientCount);
            CountDownLatch done = new CountDownLatch(clientCount);
            long start = System.nanoTime();
            for (int i = 0; i < clientCount; i++) {
                TenantPulseClient client = clients.get(i);
                int index = i;
                Thread.ofVirtual().start(() -> {
                    long deadline = start + Duration.ofSeconds(15).toNanos();
                    while (System.nanoTime() < deadline) {
                        if (client.getLong(KEY, OFFICE) == expected) {
                            seenAt.set(index, System.nanoTime() - start);
                            break;
                        }
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    done.countDown();
                });
            }
            int status = Bench.publishOffice(url, operatorToken, tenant, OFFICE, KEY, Long.toString(expected),
                    "bench-" + tenant + "-" + round);
            if (status != 200) {
                throw new IllegalStateException("publish failed with HTTP " + status);
            }
            done.await();

            long[] latencies = new long[clientCount];
            int missing = 0;
            for (int i = 0; i < clientCount; i++) {
                latencies[i] = seenAt.get(i);
                if (latencies[i] == 0) {
                    missing++;
                }
                all[filled++] = latencies[i] == 0 ? Duration.ofSeconds(15).toNanos() : latencies[i];
            }
            long[] sorted = Bench.sorted(latencies);
            System.out.printf("%-7d %-10.1f %-10.1f %-10.1f%s%n", round, Bench.percentileMillis(sorted, 50),
                    Bench.percentileMillis(sorted, 99), Bench.percentileMillis(sorted, 100),
                    missing > 0 ? "   MISSING: " + missing : "");
            Thread.sleep(300);
        }

        long[] sortedAll = Bench.sorted(all);
        System.out.printf("%nOVERALL over %d observations: p50 %.1f ms, p99 %.1f ms, max %.1f ms (limit: 5000 ms)%n",
                sortedAll.length, Bench.percentileMillis(sortedAll, 50), Bench.percentileMillis(sortedAll, 99),
                Bench.percentileMillis(sortedAll, 100));
        clients.forEach(TenantPulseClient::close);
        System.exit(0);
    }
}
