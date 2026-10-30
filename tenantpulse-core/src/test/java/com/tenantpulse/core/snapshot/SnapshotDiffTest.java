package com.tenantpulse.core.snapshot;

import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.type.NumberSettingType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotDiffTest {

    private static final String KEY = "booking.cutoff.minutes";
    private final List<SettingDefinition> definitions =
            List.of(SettingDefinition.of(KEY, new NumberSettingType(0, 1440), "60", ""));
    private final ConfigSnapshot empty = ConfigSnapshot.empty(definitions);

    @Test
    void identicalSnapshotsHaveNoDifferences() {
        assertTrue(SnapshotDiff.between(empty, empty).isEmpty());
    }

    @Test
    void reportsAddedChangedAndRemovedOverrides() {
        ConfigSnapshot from = empty.apply(1, List.of(
                SettingChange.set(KEY, Scope.client("acme"), "45"),
                SettingChange.set(KEY, Scope.office("acme", "blr"), "30")));
        ConfigSnapshot to = empty.apply(2, List.of(
                SettingChange.set(KEY, Scope.client("acme"), "40"),
                SettingChange.set(KEY, Scope.office("acme", "pune"), "20")));

        List<SnapshotDiff.Entry> diff = SnapshotDiff.between(from, to);
        assertEquals(3, diff.size());
        assertEquals("45", find(diff, Scope.client("acme")).before());
        assertEquals("40", find(diff, Scope.client("acme")).after());
        assertNull(find(diff, Scope.office("acme", "blr")).after());
        assertNull(find(diff, Scope.office("acme", "pune")).before());
    }

    @Test
    void applyingTheDiffTurnsOneSnapshotIntoTheOther() {
        ConfigSnapshot current = empty.apply(1, List.of(
                SettingChange.set(KEY, Scope.client("acme"), "45"),
                SettingChange.set(KEY, Scope.office("acme", "blr"), "30")));
        ConfigSnapshot target = empty.apply(2, List.of(SettingChange.set(KEY, Scope.office("acme", "pune"), "20")));

        ConfigSnapshot result = current.apply(3, SnapshotDiff.toChanges(SnapshotDiff.between(current, target)));
        assertEquals(target.overrideEntries().stream().map(e -> e.key() + e.scope().key() + e.value()).toList(),
                result.overrideEntries().stream().map(e -> e.key() + e.scope().key() + e.value()).toList());
    }

    private static SnapshotDiff.Entry find(List<SnapshotDiff.Entry> diff, Scope scope) {
        return diff.stream().filter(e -> e.scope().equals(scope)).findFirst().orElseThrow();
    }
}
