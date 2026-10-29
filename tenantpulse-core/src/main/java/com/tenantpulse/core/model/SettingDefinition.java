package com.tenantpulse.core.model;

import com.tenantpulse.core.type.SettingValueType;

import java.util.Objects;
import java.util.regex.Pattern;

/** What a setting is: its key, its type (which carries the validation rules) and its global default. */
public record SettingDefinition(String key, SettingValueType<?> type, Object defaultValue, String description) {

    private static final Pattern KEY_FORMAT = Pattern.compile("[a-z][a-z0-9._-]{0,127}");

    public SettingDefinition {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new IllegalArgumentException("setting key must match " + KEY_FORMAT.pattern());
        }
        Objects.requireNonNull(type, "type");
        if (!type.valueClass().isInstance(Objects.requireNonNull(defaultValue, "defaultValue"))) {
            throw new IllegalArgumentException("default value does not match the setting type");
        }
        description = description == null ? "" : description;
    }

    /** Builds a definition from a text default, which is validated by the type. */
    public static SettingDefinition of(String key, SettingValueType<?> type, String rawDefault, String description) {
        return new SettingDefinition(key, type, type.parse(rawDefault), description);
    }

    /** Parses and validates a text value against this setting's type. */
    public Object parse(String rawValue) {
        return type.parse(rawValue);
    }
}
