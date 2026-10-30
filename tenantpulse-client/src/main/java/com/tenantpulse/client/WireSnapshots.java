package com.tenantpulse.client;

import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.snapshot.ConfigSnapshot;
import com.tenantpulse.core.wire.Wire;

import java.util.Comparator;
import java.util.List;

/** Converts between the wire form of a snapshot and the in-memory {@link ConfigSnapshot}. */
final class WireSnapshots {

    private WireSnapshots() {
    }

    static ConfigSnapshot fromWire(Wire.SnapshotResponse wire) {
        List<SettingDefinition> definitions = wire.definitions().stream().map(Wire.Definition::toDefinition).toList();
        return ConfigSnapshot.restore(wire.version(), definitions, wire.overrides());
    }

    static Wire.SnapshotResponse toWire(ConfigSnapshot snapshot) {
        List<Wire.Definition> definitions = snapshot.definitions().stream().map(Wire.Definition::from)
                .sorted(Comparator.comparing(Wire.Definition::key)).toList();
        return new Wire.SnapshotResponse(snapshot.version(), definitions, snapshot.overrideEntries());
    }
}
