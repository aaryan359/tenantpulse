package com.tenantpulse.core.type;

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
