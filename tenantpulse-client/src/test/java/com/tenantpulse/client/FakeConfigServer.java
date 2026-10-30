package com.tenantpulse.client;

import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.snapshot.ConfigSnapshot;
import com.tenantpulse.core.type.NumberSettingType;
import com.tenantpulse.core.wire.Wire;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * A stand-in config server for client tests. It keeps a log of change sets like the real one, and can be switched off,
 * made to fail a few calls, or made to push any sequence of versions (duplicates, old ones, out of order).
 */
final class FakeConfigServer implements ConfigServerApi {

    static final String CUTOFF = "booking.cutoff.minutes";
    static final String NIGHT_START = "night.start.minute";
    static final String NIGHT_END = "night.end.minute";
    static final long NIGHT_GAP = 1_000_000L;

    private final List<Wire.Definition> definitions = new ArrayList<>();
    private final List<Wire.ChangeSetView> log = new ArrayList<>();
    private final BlockingQueue<Long> pushes = new LinkedBlockingQueue<>();

    volatile boolean down;
    final AtomicInteger snapshotCalls = new AtomicInteger();
    final AtomicInteger changesCalls = new AtomicInteger();
    private final AtomicInteger failNextChanges = new AtomicInteger();

    FakeConfigServer() {
        NumberSettingType cutoff = new NumberSettingType(0, 1440);
        NumberSettingType minutes = new NumberSettingType(0, Long.MAX_VALUE / 2);
        definitions.add(new Wire.Definition(CUTOFF, cutoff.kind(), cutoff.params(), "60", "cut-off"));
        definitions.add(new Wire.Definition(NIGHT_START, minutes.kind(), minutes.params(), "0", "night start"));
        definitions.add(new Wire.Definition(NIGHT_END, minutes.kind(), minutes.params(), Long.toString(NIGHT_GAP),
                "night end"));
    }

    // ------------------------------------------------------------ test controls

    synchronized long publish(Wire.Change... changes) {
        long version = log.size() + 1L;
        log.add(new Wire.ChangeSetView(version, "tester", "test", "PUBLISH", Instant.now(), List.of(changes)));
        return version;
    }

    long publishCutoff(String office, long minutes) {
        return publish(new Wire.Change(CUTOFF, Scope.office("acme", office), Long.toString(minutes)));
    }

    /** One change set that moves the night window's start and end together. */
    long publishNightWindow(long start) {
        return publish(
                new Wire.Change(NIGHT_START, Scope.client("acme"), Long.toString(start)),
                new Wire.Change(NIGHT_END, Scope.client("acme"), Long.toString(start + NIGHT_GAP)));
    }

    synchronized void addDefinition(Wire.Definition definition) {
        definitions.add(definition);
    }

    /** Forget everything after {@code keepVersions}, as if storage had been restored from an old backup. */
    synchronized void rewind(int keepVersions) {
        while (log.size() > keepVersions) {
            log.remove(log.size() - 1);
        }
    }

    void push(long version) {
        pushes.add(version);
    }

    void failNextChangeCalls(int count) {
        failNextChanges.set(count);
    }

    synchronized long latest() {
        return log.size();
    }

    // ------------------------------------------------------------ ConfigServerApi

    @Override
    public synchronized Wire.SnapshotResponse snapshot() {
        checkUp();
        snapshotCalls.incrementAndGet();
        List<SettingDefinition> defs = definitions.stream().map(Wire.Definition::toDefinition).toList();
        ConfigSnapshot state = ConfigSnapshot.empty(defs);
        for (Wire.ChangeSetView view : log) {
            state = state.apply(view.version(), view.changes().stream()
                    .map(c -> new SettingChange(c.key(), c.scope(), c.value())).toList());
        }
        return WireSnapshots.toWire(state);
    }

    @Override
    public synchronized Wire.ChangesResponse changes(long since) {
        checkUp();
        changesCalls.incrementAndGet();
        if (failNextChanges.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new ServerException(ServerException.Kind.UNAVAILABLE, "injected failure");
        }
        long upTo = log.size();
        if (since > upTo) {
            return new Wire.ChangesResponse(since, upTo, true, List.of());
        }
        return new Wire.ChangesResponse(since, upTo, false,
                log.stream().filter(view -> view.version() > since).toList());
    }

    @Override
    public synchronized Wire.VersionResponse version(long clientVersion) {
        checkUp();
        return new Wire.VersionResponse(log.size(), log.size());
    }

    @Override
    public void stream(LongConsumer onVersion) {
        checkUp();
        try {
            while (!down) {
                Long version = pushes.poll(20, TimeUnit.MILLISECONDS);
                if (version != null) {
                    onVersion.accept(version);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        throw new ServerException(ServerException.Kind.UNAVAILABLE, "connection dropped");
    }

    private void checkUp() {
        if (down) {
            throw new ServerException(ServerException.Kind.UNAVAILABLE, "server is down");
        }
    }
}
