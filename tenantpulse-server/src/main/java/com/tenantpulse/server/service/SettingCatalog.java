package com.tenantpulse.server.service;

import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.type.DurationSettingType;
import com.tenantpulse.core.type.EnumSettingType;
import com.tenantpulse.core.type.ListSettingType;
import com.tenantpulse.core.type.NumberSettingType;
import com.tenantpulse.core.type.TimeWindowSettingType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** The settings the platform knows about. Hard-coded for now; a later feature stores them in the database. */
@Component
public class SettingCatalog {

    private final List<SettingDefinition> definitions = List.of(
            SettingDefinition.of("booking.cutoff.minutes", new NumberSettingType(0, 1440), "60",
                    "Minutes before departure when booking closes"),
            SettingDefinition.of("ride.max.duration", new DurationSettingType(Duration.ofMinutes(10), Duration.ofHours(12)),
                    "PT3H", "Longest allowed ride"),
            SettingDefinition.of("night.safety.window", new TimeWindowSettingType(true), "22:00-06:00",
                    "Daily window when night-safety rules apply"),
            SettingDefinition.of("escort.rule", new EnumSettingType(Set.of("REQUIRED", "OPTIONAL", "NONE")), "OPTIONAL",
                    "Whether an escort is needed on night rides"),
            SettingDefinition.of("cab.types.allowed", new ListSettingType(Set.of("SEDAN", "SUV", "HATCHBACK", "VAN"), 4),
                    "SEDAN,SUV", "Cab types riders may book"));

    public List<SettingDefinition> definitions() {
        return definitions;
    }
}
