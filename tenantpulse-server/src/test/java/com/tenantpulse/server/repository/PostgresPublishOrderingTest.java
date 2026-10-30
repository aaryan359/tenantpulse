package com.tenantpulse.server.repository;

import com.tenantpulse.core.model.ChangeKind;
import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.SettingChange;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version guarantees on a real Postgres: the single counter row serialises publishers, so versions are distinct and
 * gap-free, and a reader that has seen version N has seen every version below N (a plain SEQUENCE cannot promise that).
 * Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresPublishOrderingTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ChangeSetRepository repository;

    private static ChangeSet cutoff(String tenant, int minutes, String key) {
        return new ChangeSet("tester", "load", key, List.of(
                SettingChange.set("booking.cutoff.minutes", Scope.client(tenant), Integer.toString(minutes))));
    }

    @Test
    void concurrentPublishersGetDistinctContiguousVersions() throws Exception {
        long start = repository.latestVersion();
        int threads = 24;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String tenant = "pg-" + t;
            futures.add(pool.submit(() -> {
                go.await();
                List<Long> mine = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    mine.add(repository.publish(cutoff(tenant, 10 + i, null)).version());
                }
                return mine;
            }));
        }
        go.countDown();

        Set<Long> all = new TreeSet<>();
        for (Future<List<Long>> future : futures) {
            all.addAll(future.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();

        int total = threads * perThread;
        assertEquals(total, all.size(), "a version was handed out twice");
        assertEquals(start + total, ((TreeSet<Long>) all).last(), "a version was skipped");
        List<PublishedChangeSet> log = repository.findSince(start, total + 10);
        for (int i = 0; i < log.size(); i++) {
            assertEquals(start + i + 1, log.get(i).version());
        }
    }

    @Test
    void aReaderNeverSeesAGapWhilePublishersRun() throws Exception {
        long start = repository.latestVersion();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> problem = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(10);

        Future<?> reader = pool.submit(() -> {
            while (!done.get()) {
                List<PublishedChangeSet> seen = repository.findSince(start, 10_000);
                for (int i = 0; i < seen.size(); i++) {
                    if (seen.get(i).version() != start + i + 1) {
                        problem.set("hole in the log: expected " + (start + i + 1) + " but saw " + seen.get(i).version());
                        return;
                    }
                }
            }
        });
        List<Future<?>> writers = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            String tenant = "gap-" + t;
            writers.add(pool.submit(() -> {
                for (int i = 0; i < 25; i++) {
                    repository.publish(cutoff(tenant, 10 + i, null));
                }
            }));
        }
        for (Future<?> writer : writers) {
            writer.get(120, TimeUnit.SECONDS);
        }
        done.set(true);
        reader.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertNull(problem.get(), problem.get());
    }

    @Test
    void identicalRequestsRacingForTheSameKeyCreateOneVersion() throws Exception {
        String key = "race-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int t = 0; t < 30; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                return repository.publish(cutoff("race", 33, key)).version();
            }));
        }
        go.countDown();
        Set<Long> versions = new TreeSet<>();
        for (Future<Long> future : futures) {
            versions.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, versions.size(), "got versions " + versions);
    }

    @Test
    void everythingIsStoredAndReadBackExactly() {
        ChangeSet original = new ChangeSet("alice-id", "because", "round-trip-" + System.nanoTime(),
                ChangeKind.ROLLBACK, 3L, List.of(
                SettingChange.set("booking.cutoff.minutes", Scope.shift("acme", "blr", "night"), "15"),
                SettingChange.remove("escort.rule", Scope.office("acme", "blr")),
                SettingChange.set("night.safety.window", Scope.global(), "21:00-05:00")));
        PublishedChangeSet saved = repository.publish(original);

        PublishedChangeSet loaded = repository.findByVersion(saved.version()).orElseThrow();
        assertEquals(original, loaded.changeSet());
        assertEquals(saved.publishedAt(), loaded.publishedAt());
        assertTrue(repository.findPromotionOf(3L).isEmpty());
        assertEquals(original.changes().get(1).rawValue(), loaded.changeSet().changes().get(1).rawValue());
    }
}
