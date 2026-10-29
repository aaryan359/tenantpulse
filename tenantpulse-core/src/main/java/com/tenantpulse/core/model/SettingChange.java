package com.tenantpulse.core.model;

import java.util.Objects;

/** One edit: set {@code key} at {@code scope} to {@code rawValue}, or remove the override when it is null. */
public record SettingChange(String key, Scope scope, String rawValue) {

    public SettingChange {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(scope, "scope");
    }

    public static SettingChange set(String key, Scope scope, String rawValue) {
        return new SettingChange(key, scope, Objects.requireNonNull(rawValue, "rawValue"));
    }

    public static SettingChange remove(String key, Scope scope) {
        return new SettingChange(key, scope, null);
    }

    public boolean isRemoval() {
        return rawValue == null;
    }
}
