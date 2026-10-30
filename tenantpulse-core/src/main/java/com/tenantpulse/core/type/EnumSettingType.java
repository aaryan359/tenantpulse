package com.tenantpulse.core.type;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** One of a fixed set of allowed strings (case-sensitive, no commas). */
public final class EnumSettingType extends SettingValueType<String> {

    private final Set<String> allowed;

    public EnumSettingType(Set<String> allowed) {
        super(String.class);
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("an enum needs at least one allowed value");
        }
        if (allowed.stream().anyMatch(v -> v.isBlank() || v.contains(","))) {
            throw new IllegalArgumentException("enum values must be non-blank and contain no commas");
        }
        this.allowed = Set.copyOf(allowed);
    }

    @Override
    public String name() {
        return "enum";
    }

    @Override
    public String kind() {
        return "enum";
    }

    @Override
    public Map<String, String> params() {
        return Map.of("allowed", String.join(",", new TreeSet<>(allowed)));
    }

    @Override
    public String format(String value) {
        return value;
    }

    @Override
    protected String doParse(String raw) {
        return raw;
    }

    @Override
    protected void check(String value) {
        if (!allowed.contains(value)) {
            throw invalid("value must be one of " + new TreeSet<>(allowed));
        }
    }
}
