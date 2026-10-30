package com.tenantpulse.core.type;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Rebuilds a {@link SettingValueType} from its wire form ({@code kind} plus {@code params}). */
public final class SettingTypes {

    private SettingTypes() {
    }

    public static SettingValueType<?> create(String kind, Map<String, String> params) {
        return switch (kind) {
            case "number" -> new NumberSettingType(Long.parseLong(params.get("min")), Long.parseLong(params.get("max")));
            case "duration" -> new DurationSettingType(Duration.parse(params.get("min")), Duration.parse(params.get("max")));
            case "time_window" -> new TimeWindowSettingType(Boolean.parseBoolean(params.get("allowOvernight")));
            case "enum" -> new EnumSettingType(split(params.get("allowed")));
            case "list" -> new ListSettingType(split(params.getOrDefault("allowed", "")),
                    Integer.parseInt(params.get("maxSize")));
            default -> throw new IllegalArgumentException("unknown setting type '" + kind + "'");
        };
    }

    private static Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(",")).collect(Collectors.toUnmodifiableSet());
    }
}
