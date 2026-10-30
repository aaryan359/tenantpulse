package com.tenantpulse.core.type;

import java.util.Map;

/** A whole number within an inclusive range. */
public final class NumberSettingType extends SettingValueType<Long> {

    private final long min;
    private final long max;

    public NumberSettingType(long min, long max) {
        super(Long.class);
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        this.min = min;
        this.max = max;
    }

    @Override
    public String name() {
        return "number";
    }

    @Override
    public String kind() {
        return "number";
    }

    @Override
    public Map<String, String> params() {
        return Map.of("min", Long.toString(min), "max", Long.toString(max));
    }

    @Override
    public String format(Long value) {
        return Long.toString(value);
    }

    @Override
    protected Long doParse(String raw) {
        return Long.parseLong(raw);
    }

    @Override
    protected void check(Long value) {
        if (value < min || value > max) {
            throw invalid("number must be between " + min + " and " + max);
        }
    }
}
