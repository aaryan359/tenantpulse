package com.tenantpulse.core.type;

import com.tenantpulse.core.exception.InvalidSettingValueException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingValueTypesTest {

    @Test
    void numberAcceptsValuesInRange() {
        NumberSettingType type = new NumberSettingType(0, 1440);
        assertEquals(30L, type.parse("30"));
        assertEquals(0L, type.parse("0"));
        assertEquals(1440L, type.parse(" 1440 "));
    }

    @Test
    void numberRejectsNegativeCutoff() {
        NumberSettingType type = new NumberSettingType(0, 1440);
        assertThrows(InvalidSettingValueException.class, () -> type.parse("-30"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("1441"));
    }

    @Test
    void numberRejectsGarbageAndBlank() {
        NumberSettingType type = new NumberSettingType(0, 10);
        assertThrows(InvalidSettingValueException.class, () -> type.parse("abc"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse(""));
        assertThrows(InvalidSettingValueException.class, () -> type.parse(null));
    }

    @Test
    void durationParsesIsoAndEnforcesRange() {
        DurationSettingType type = new DurationSettingType(Duration.ZERO, Duration.ofHours(12));
        assertEquals(Duration.ofMinutes(30), type.parse("PT30M"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("PT-30M"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("PT13H"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("30 minutes"));
    }

    @Test
    void timeWindowRejectsEndBeforeStartWhenOvernightNotAllowed() {
        TimeWindowSettingType type = new TimeWindowSettingType(false);
        assertEquals(new TimeWindow(LocalTime.of(9, 0), LocalTime.of(17, 0)), type.parse("09:00-17:00"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("17:00-09:00"));
    }

    @Test
    void timeWindowAllowsOvernightWhenDeclared() {
        TimeWindowSettingType type = new TimeWindowSettingType(true);
        TimeWindow night = type.parse("22:00-06:00");
        assertTrue(night.isOvernight());
        assertTrue(night.contains(LocalTime.of(23, 30)));
        assertTrue(night.contains(LocalTime.of(2, 0)));
        assertFalse(night.contains(LocalTime.of(12, 0)));
    }

    @Test
    void timeWindowRejectsEmptyWindowAndMalformedInput() {
        TimeWindowSettingType type = new TimeWindowSettingType(true);
        assertThrows(InvalidSettingValueException.class, () -> type.parse("10:00-10:00"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("10:00"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("10:00-11:00-12:00"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("25:00-26:00"));
    }

    @Test
    void enumAcceptsOnlyAllowedValues() {
        EnumSettingType type = new EnumSettingType(Set.of("STRICT", "RELAXED"));
        assertEquals("STRICT", type.parse("STRICT"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("strict"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("OTHER"));
    }

    @Test
    void listParsesTrimsAndValidates() {
        ListSettingType type = new ListSettingType(Set.of("SEDAN", "SUV", "HATCHBACK"), 3);
        assertEquals(List.of("SEDAN", "SUV"), type.parse("SEDAN, SUV"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("SEDAN,SEDAN"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("SEDAN,,SUV"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("SEDAN,BUS"));
    }

    @Test
    void listEnforcesMaxSize() {
        ListSettingType type = new ListSettingType(Set.of(), 2);
        assertEquals(List.of("a", "b"), type.parse("a,b"));
        assertThrows(InvalidSettingValueException.class, () -> type.parse("a,b,c"));
    }

    @Test
    void formatRoundTrips() {
        TimeWindowSettingType window = new TimeWindowSettingType(true);
        assertEquals(window.parse("22:00-06:00"), window.parse(window.format(window.parse("22:00-06:00"))));
        ListSettingType list = new ListSettingType(Set.of(), 5);
        assertEquals(list.parse("a,b"), list.parse(list.format(list.parse("a,b"))));
    }

    @Test
    void errorMessagesDoNotEchoTheInput() {
        NumberSettingType type = new NumberSettingType(0, 10);
        InvalidSettingValueException e =
                assertThrows(InvalidSettingValueException.class, () -> type.parse("secret-9876543210"));
        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    void everyTypeCanBeRebuiltFromItsWireDescription() {
        List<SettingValueType<?>> types = List.of(
                new NumberSettingType(0, 1440),
                new DurationSettingType(Duration.ofMinutes(10), Duration.ofHours(12)),
                new TimeWindowSettingType(true),
                new EnumSettingType(Set.of("A", "B")),
                new ListSettingType(Set.of("X", "Y"), 2),
                new ListSettingType(Set.of(), 4));
        for (SettingValueType<?> original : types) {
            SettingValueType<?> rebuilt = SettingTypes.create(original.kind(), original.params());
            assertEquals(original.kind(), rebuilt.kind());
            assertEquals(original.params(), rebuilt.params());
        }
    }

    @Test
    void rebuiltTypeEnforcesTheSameRules() {
        SettingValueType<?> rebuilt = SettingTypes.create("number", new NumberSettingType(0, 1440).params());
        assertThrows(InvalidSettingValueException.class, () -> rebuilt.parse("-30"));
    }

    @Test
    void unknownTypeKindIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SettingTypes.create("matrix", java.util.Map.of()));
    }
}
