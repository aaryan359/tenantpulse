package com.tenantpulse.server.service;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.server.repository.ChangeSetRepository;
import com.tenantpulse.server.security.Caller;
import com.tenantpulse.server.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hard rule: versions are assigned one at a time, never repeat and never skip, however many publishers run at once.
 * (The same guarantees on a real Postgres are checked in {@code PostgresPublishOrderingTest}.)
 */
@SpringBootTest
@ActiveProfiles("memory")
class ParallelPublishOrderingTest {

    private static final int THREADS = 16;
    private static final int PER_THREAD = 25;

    @Autowired
    ChangeSetService service;
    @Autowired
    ChangeSetRepository repository;
    @Autowired
    ConfigStateService state;

    @Test
    void concurrentPublishersGetDistinctContiguousVersions() throws Exception {
        long start = repository.latestVersion();
        Caller admin = new Caller("tester", Role.ADMIN, null);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            String tenant = "par-" + UUID.randomUUID().toString().substring(0, 6);
            futures.add(pool.submit(() -> {
                go.await();
                List<Long> mine = new ArrayList<>();
                for (int i = 0; i < PER_THREAD; i++) {
                    ChangeSet changeSet = new ChangeSet("tester", "load", null, List.of(
                            SettingChange.set("booking.cutoff.minutes", Scope.client(tenant), Integer.toString(10 + i))));
                    mine.add(service.publish(admin, changeSet).version());
                }
                return mine;
            }));
        }
        go.countDown();

        Set<Long> all = new TreeSet<>();
        for (Future<List<Long>> future : futures) {
            List<Long> mine = future.get(60, TimeUnit.SECONDS);
            for (int i = 1; i < mine.size(); i++) {
                assertTrue(mine.get(i) > mine.get(i - 1), "one publisher saw versions go backwards");
            }
            all.addAll(mine);
        }
        pool.shutdown();

        int total = THREADS * PER_THREAD;
        assertEquals(total, all.size(), "a version was handed out twice");
        assertEquals(start + 1, ((TreeSet<Long>) all).first());
        assertEquals(start + total, ((TreeSet<Long>) all).last(), "a version was skipped");
        assertEquals(start + total, repository.latestVersion());
    }

    @Test
    void theLogReadsBackInVersionOrderWithNoGaps() {
        Caller admin = new Caller("tester", Role.ADMIN, null);
        for (int i = 0; i < 5; i++) {
            service.publish(admin, new ChangeSet("tester", "x", null, List.of(
                    SettingChange.set("escort.rule", Scope.client("order-" + i), "NONE"))));
        }
        List<PublishedChangeSet> log = repository.findSince(0, 100_000);
        for (int i = 0; i < log.size(); i++) {
            assertEquals(i + 1L, log.get(i).version());
        }
        assertEquals(repository.latestVersion(), state.snapshot().version(),
                "the server's in-memory state is current with storage");
    }

    @Test
    void manyIdenticalRequestsAtOnceCreateExactlyOneVersion() throws Exception {
        Caller admin = new Caller("tester", Role.ADMIN, null);
        String key = "dup-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int t = 0; t < 32; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                return service.publish(admin, new ChangeSet("tester", "retry storm", key, List.of(
                        SettingChange.set("escort.rule", Scope.client("dup-tenant"), "NONE")))).version();
            }));
        }
        go.countDown();
        Set<Long> versions = new TreeSet<>();
        for (Future<Long> future : futures) {
            versions.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, versions.size(), "the same request must produce one version, got " + versions);
    }
}
