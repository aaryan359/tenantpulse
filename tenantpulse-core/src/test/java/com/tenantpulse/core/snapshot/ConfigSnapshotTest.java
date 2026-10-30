package com.tenantpulse.core.snapshot;

import com.tenantpulse.core.exception.InvalidSettingValueException;
import com.tenantpulse.core.exception.StaleVersionException;
import com.tenantpulse.core.exception.UnknownSettingException;
import com.tenantpulse.core.model.OverrideEntry;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeContext;
import com.tenantpulse.core.model.ScopeLevel;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.type.NumberSettingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigSnapshotTest {

    private static final String CUTOFF = "booking.cutoff.minutes";
    private static final ScopeContext BLR = ScopeContext.office("acme", "blr");
    private static final ScopeContext PUNE = ScopeContext.office("acme", "pune");

    private List<SettingDefinition> definitions;
    private ConfigSnapshot v0;

    @BeforeEach
    void setUp() {
        definitions = List.of(
                SettingDefinition.of(CUTOFF, new NumberSettingType(0, 1440), "60", "booking cut-off"));
        v0 = ConfigSnapshot.empty(definitions);
    }

    @Test
    void startsAtVersionZeroWithDefaults() {
        assertEquals(0L, v0.version());
        assertEquals(60L, v0.get(CUTOFF, BLR, Long.class));
    }

    @Test
    void officeOverrideAffectsOnlyThatOffice() {
        ConfigSnapshot v1 = v0.apply(1, List.of(SettingChange.set(CUTOFF, Scope.office("acme", "blr"), "30")));
        assertEquals(30L, v1.get(CUTOFF, BLR, Long.class));
        assertEquals(60L, v1.get(CUTOFF, PUNE, Long.class));
        assertEquals(1L, v1.version());
    }

    @Test
    void applyLeavesThePreviousSnapshotUntouched() {
        ConfigSnapshot v1 = v0.apply(1, List.of(SettingChange.set(CUTOFF, Scope.client("acme"), "30")));
        assertEquals(60L, v0.get(CUTOFF, BLR, Long.class));
        assertEquals(30L, v1.get(CUTOFF, BLR, Long.class));
    }

    @Test
    void removingAnOverrideRestoresTheInheritedValue() {
        ConfigSnapshot v1 = v0.apply(1, List.of(SettingChange.set(CUTOFF, Scope.office("acme", "blr"), "30")));
        ConfigSnapshot v2 = v1.apply(2, List.of(SettingChange.remove(CUTOFF, Scope.office("acme", "blr"))));
        assertEquals(60L, v2.get(CUTOFF, BLR, Long.class));
        assertEquals(ScopeLevel.GLOBAL, v2.explain(CUTOFF, BLR).level());
    }

    @Test
    void explainReportsLevelAndVersionOfTheWinningOverride() {
        ConfigSnapshot v5 = v0.apply(5, List.of(SettingChange.set(CUTOFF, Scope.office("acme", "blr"), "30")));
        var explained = v5.explain(CUTOFF, BLR);
        assertEquals(ScopeLevel.OFFICE, explained.level());
        assertEquals(5L, explained.version());
    }

    @Test
    void versionsOnlyMoveForward() {
        ConfigSnapshot v43 = v0.apply(43, List.of(SettingChange.set(CUTOFF, Scope.client("acme"), "30")));
        StaleVersionException older = assertThrows(StaleVersionException.class,
                () -> v43.apply(41, List.of(SettingChange.set(CUTOFF, Scope.client("acme"), "99"))));
        assertEquals(43L, older.currentVersion());
        assertEquals(41L, older.offeredVersion());
        assertThrows(StaleVersionException.class, () -> v43.apply(43, List.of()));
        assertEquals(30L, v43.get(CUTOFF, BLR, Long.class));
    }

    @Test
    void invalidValueRejectsTheWholeChangeSet() {
        ConfigSnapshot before = v0;
        assertThrows(InvalidSettingValueException.class, () -> before.apply(1, List.of(
                SettingChange.set(CUTOFF, Scope.client("acme"), "30"),
                SettingChange.set(CUTOFF, Scope.office("acme", "blr"), "-30"))));
        assertEquals(60L, before.get(CUTOFF, BLR, Long.class));
        assertEquals(0L, before.version());
    }

    @Test
    void unknownKeyIsRejected() {
        assertThrows(UnknownSettingException.class,
                () -> v0.apply(1, List.of(SettingChange.set("no.such.key", Scope.global(), "1"))));
        assertThrows(UnknownSettingException.class, () -> v0.get("no.such.key", BLR));
    }

    @Test
    void laterChangeToTheSameKeyAndScopeInOneSetWins() {
        ConfigSnapshot v1 = v0.apply(1, List.of(
                SettingChange.set(CUTOFF, Scope.client("acme"), "30"),
                SettingChange.set(CUTOFF, Scope.client("acme"), "20")));
        assertEquals(20L, v1.get(CUTOFF, BLR, Long.class));
    }

    @Test
    void emptyChangeListJustAdvancesTheVersion() {
        ConfigSnapshot v1 = v0.apply(1, List.of(SettingChange.set(CUTOFF, Scope.client("acme"), "30")));
        ConfigSnapshot v9 = v1.apply(9, List.of());
        assertEquals(9L, v9.version());
        assertEquals(30L, v9.get(CUTOFF, BLR, Long.class));
    }

    @Test
    void restoreKeepsThePerOverrideVersion() {
        ConfigSnapshot restored = ConfigSnapshot.restore(7, definitions, List.of(
                new OverrideEntry(CUTOFF, Scope.office("acme", "blr"), "30", 4),
                new OverrideEntry(CUTOFF, Scope.client("acme"), "45", 6)));
        assertEquals(7L, restored.version());
        assertEquals(4L, restored.explain(CUTOFF, BLR).version());
        assertEquals(6L, restored.explain(CUTOFF, PUNE).version());
    }

    @Test
    void restoreRejectsAnOverrideNewerThanTheSnapshot() {
        assertThrows(IllegalArgumentException.class, () -> ConfigSnapshot.restore(3, definitions,
                List.of(new OverrideEntry(CUTOFF, Scope.client("acme"), "45", 9))));
    }

    @Test
    void overrideEntriesRoundTripThroughRestore() {
        ConfigSnapshot v2 = v0.apply(1, List.of(SettingChange.set(CUTOFF, Scope.client("acme"), "45")))
                .apply(2, List.of(SettingChange.set(CUTOFF, Scope.office("acme", "blr"), "30")));
        ConfigSnapshot copy = ConfigSnapshot.restore(v2.version(), definitions, v2.overrideEntries());
        assertEquals(v2.overrideEntries(), copy.overrideEntries());
        assertEquals(30L, copy.get(CUTOFF, BLR, Long.class));
    }
}
