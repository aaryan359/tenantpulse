package com.tenantpulse.client;

import com.tenantpulse.core.model.OverrideEntry;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeLevel;
import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.snapshot.ConfigSnapshot;
import com.tenantpulse.core.type.DurationSettingType;
import com.tenantpulse.core.type.EnumSettingType;
import com.tenantpulse.core.type.ListSettingType;
import com.tenantpulse.core.type.NumberSettingType;
import com.tenantpulse.core.type.TimeWindow;
import com.tenantpulse.core.type.TimeWindowSettingType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The typed readers a service uses, one per setting type, and how office and shift overrides apply. */
class ConfigViewTest {

    private static final List<SettingDefinition> DEFINITIONS = List.of(
            SettingDefinition.of("cutoff", new NumberSettingType(0, 1440), "60", ""),
            SettingDefinition.of("max.ride", new DurationSettingType(Duration.ofMinutes(10), Duration.ofHours(12)), "PT3H", ""),
            SettingDefinition.of("night", new TimeWindowSettingType(true), "22:00-06:00", ""),
            SettingDefinition.of("escort", new EnumSettingType(Set.of("REQUIRED", "OPTIONAL")), "OPTIONAL", ""),
            SettingDefinition.of("cabs", new ListSettingType(Set.of("SEDAN", "SUV"), 2), "SEDAN", ""));

    private final ConfigView view = new ConfigView(ConfigSnapshot.restore(3, DEFINITIONS, List.of(
            new OverrideEntry("cutoff", Scope.office("acme", "blr"), "30", 1),
            new OverrideEntry("cutoff", Scope.shift("acme", "blr", "night"), "15", 2),
            new OverrideEntry("max.ride", Scope.client("acme"), "PT90M", 3),
            new OverrideEntry("night", Scope.office("acme", "blr"), "21:00-05:00", 3),
            new OverrideEntry("escort", Scope.client("acme"), "REQUIRED", 3),
            new OverrideEntry("cabs", Scope.client("acme"), "SEDAN,SUV", 3))), "acme");

    @Test
    void everyTypeIsReadAsItsOwnJavaType() {
        assertEquals(30L, view.getLong("cutoff", "blr"));
        assertEquals(Duration.ofMinutes(90), view.getDuration("max.ride", "blr"));
        assertEquals(new TimeWindow(LocalTime.of(21, 0), LocalTime.of(5, 0)), view.getTimeWindow("night", "blr"));
        assertEquals("REQUIRED", view.getString("escort", "blr"));
        assertEquals(List.of("SEDAN", "SUV"), view.getList("cabs", "blr"));
    }

    @Test
    void officesWithoutAnOverrideFallBackToTheClientThenTheDefault() {
        assertEquals(60L, view.getLong("cutoff", "pune"));
        assertEquals(Duration.ofMinutes(90), view.getDuration("max.ride", "pune"));
        assertEquals(new TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)), view.getTimeWindow("night", "pune"));
    }

    @Test
    void aShiftOverrideBeatsTheOfficeOne() {
        assertEquals(15L, view.get("cutoff", "blr", "night", Long.class));
        assertEquals(30L, view.get("cutoff", "blr", "day", Long.class));
    }

    @Test
    void theTenantLevelValueIgnoresOfficeAndShiftOverrides() {
        assertEquals(60L, view.get("cutoff", Long.class));
    }

    @Test
    void explainSaysWhichLevelSuppliedTheValue() {
        assertEquals(ScopeLevel.SHIFT, view.explain("cutoff", "blr", "night").level());
        assertEquals(ScopeLevel.OFFICE, view.explain("cutoff", "blr", "day").level());
        assertEquals(ScopeLevel.GLOBAL, view.explain("cutoff", "pune", null).level());
    }

    @Test
    void aViewDoesNotChangeAfterItIsTaken() {
        assertEquals(3L, view.version());
    }

    @Test
    void anUnknownSettingIsAnError() {
        assertThrows(RuntimeException.class, () -> view.getLong("no.such.setting", "blr"));
    }
}
