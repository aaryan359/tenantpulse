package com.tenantpulse.core.snapshot;

import com.tenantpulse.core.model.OverrideEntry;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.SettingChange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Compares two snapshots override by override. Used to show what a rollback will change, and to perform it. */
public final class SnapshotDiff {

    /** One override that differs. {@code before} or {@code after} is null when it does not exist on that side. */
    public record Entry(String key, Scope scope, String before, String after) {
    }

    private SnapshotDiff() {
    }

    /** The overrides that must change to turn {@code from} into {@code to}, ordered by key then scope. */
    public static List<Entry> between(ConfigSnapshot from, ConfigSnapshot to) {
        Map<String, OverrideEntry> before = index(from);
        Map<String, OverrideEntry> after = index(to);
        TreeMap<String, Entry> differences = new TreeMap<>();
        before.forEach((id, old) -> {
            OverrideEntry now = after.get(id);
            String newValue = now == null ? null : now.value();
            if (!Objects.equals(old.value(), newValue)) {
                differences.put(id, new Entry(old.key(), old.scope(), old.value(), newValue));
            }
        });
        after.forEach((id, now) -> {
            if (!before.containsKey(id)) {
                differences.put(id, new Entry(now.key(), now.scope(), null, now.value()));
            }
        });
        return new ArrayList<>(differences.values());
    }

    /** The changes that apply the given differences: set the new value, or remove the override. */
    public static List<SettingChange> toChanges(List<Entry> entries) {
        List<SettingChange> changes = new ArrayList<>();
        for (Entry entry : entries) {
            changes.add(entry.after() == null
                    ? SettingChange.remove(entry.key(), entry.scope())
                    : SettingChange.set(entry.key(), entry.scope(), entry.after()));
        }
        return changes;
    }

    private static Map<String, OverrideEntry> index(ConfigSnapshot snapshot) {
        Map<String, OverrideEntry> byId = new HashMap<>();
        for (OverrideEntry entry : snapshot.overrideEntries()) {
            byId.put(entry.key() + "@" + entry.scope().key(), entry);
        }
        return byId;
    }
}
