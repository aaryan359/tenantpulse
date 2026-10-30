package com.tenantpulse.benchmarks;

import com.tenantpulse.client.ClientConfig;
import com.tenantpulse.client.ConfigServerApi;
import com.tenantpulse.client.ConfigView;
import com.tenantpulse.client.TenantPulseClient;
import com.tenantpulse.core.model.OverrideEntry;
import com.tenantpulse.core.type.NumberSettingType;
import com.tenantpulse.core.wire.Wire;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Local read speed: how long {@code client.getLong(...)} takes. The snapshot holds 500 settings with overrides at
 * client, office and shift level, as a busy tenant would. No server is involved; a stand-in feeds the first snapshot.
 *
 * <p>Run with {@code java -cp ... com.tenantpulse.benchmarks.LocalReadBenchmark}; JMH prints throughput and the
 * sampled latency percentiles (p50, p99, ...).
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class LocalReadBenchmark {

    private static final int SETTINGS = 500;

    private TenantPulseClient client;
    private ConfigView view;

    @Setup(Level.Trial)
    public void setUp() {
        List<Wire.Definition> definitions = new ArrayList<>();
        List<OverrideEntry> overrides = new ArrayList<>();
        for (int i = 0; i < SETTINGS; i++) {
            String key = "setting." + i;
            definitions.add(new Wire.Definition(key, new NumberSettingType(0, 100_000).kind(),
                    new NumberSettingType(0, 100_000).params(), "60", "benchmark setting"));
            if (i % 2 == 0) {
                overrides.add(new OverrideEntry(key, com.tenantpulse.core.model.Scope.client("acme"), "45", 1));
            }
            if (i % 5 == 0) {
                overrides.add(new OverrideEntry(key, com.tenantpulse.core.model.Scope.office("acme", "blr"), "30", 1));
            }
            if (i % 10 == 0) {
                overrides.add(new OverrideEntry(key, com.tenantpulse.core.model.Scope.shift("acme", "blr", "night"), "15", 1));
            }
        }
        Wire.SnapshotResponse snapshot = new Wire.SnapshotResponse(1, definitions, overrides);
        client = TenantPulseClient.create(
                ClientConfig.builder(URI.create("http://unused.invalid"), "acme", () -> "unused").build(),
                new ConfigServerApi() {
                    @Override
                    public Wire.SnapshotResponse snapshot() {
                        return snapshot;
                    }

                    @Override
                    public Wire.ChangesResponse changes(long since) {
                        return new Wire.ChangesResponse(since, since, false, List.of());
                    }

                    @Override
                    public Wire.VersionResponse version(long clientVersion) {
                        return new Wire.VersionResponse(1, 1);
                    }

                    @Override
                    public void stream(LongConsumer onVersion) {
                        try {
                            Thread.sleep(Long.MAX_VALUE);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        client.start();
        view = client.view();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        client.close();
    }

    /** Resolves from an office-level override (found on the second lookup). */
    @Benchmark
    @Threads(1)
    public long readOfficeOverride_1thread() {
        return client.getLong("setting.10", "blr");
    }

    /** The worst case: nothing overrides the key, so every level is checked before the default is returned. */
    @Benchmark
    @Threads(1)
    public long readFallsThroughToDefault_1thread() {
        return client.getLong("setting.1", "pune");
    }

    /** Eight threads reading at once: reads share nothing and take no lock, so this should scale. */
    @Benchmark
    @Threads(8)
    public long readOfficeOverride_8threads() {
        return client.getLong("setting.10", "blr");
    }

    /** Reading through a view held by the caller skips the snapshot lookup. */
    @Benchmark
    @Threads(1)
    public long readThroughHeldView_1thread() {
        return view.getLong("setting.10", "blr");
    }

    public static void main(String[] args) throws Exception {
        Options options = new OptionsBuilder().include(LocalReadBenchmark.class.getSimpleName()).build();
        new Runner(options).run();
    }
}
