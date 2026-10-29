package com.tenantpulse.core.type;

import java.util.LinkedHashSet;
import java.util.Set;

/** One of a fixed set of allowed strings (case-sensitive). */
public final class EnumSettingType extends SettingValueType<String> {

    private final Set<String> allowed;

    public EnumSettingType(Set<String> allowed) {
        super(String.class);
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("an enum needs at least one allowed value");
        }
        this.allowed = Set.copyOf(new LinkedHashSet<>(allowed));
    }

    @Override
    public String name() {
        return "enum";
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
            throw invalid("value must be one of " + new java.util.TreeSet<>(allowed));
        }
    }
}
