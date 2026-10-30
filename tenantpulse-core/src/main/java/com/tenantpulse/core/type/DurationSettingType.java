package com.tenantpulse.core.type;

import java.time.Duration;
import java.util.Map;

/** An ISO-8601 duration such as {@code PT30M}, within an inclusive range. */
public final class DurationSettingType extends SettingValueType<Duration> {

    private final Duration min;
    private final Duration max;

    public DurationSettingType(Duration min, Duration max) {
        super(Duration.class);
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        this.min = min;
        this.max = max;
    }

    @Override
    public String name() {
        return "duration";
    }

    @Override
    public String kind() {
        return "duration";
    }

    @Override
    public Map<String, String> params() {
        return Map.of("min", min.toString(), "max", max.toString());
    }

    @Override
    public String format(Duration value) {
        return value.toString();
    }

    @Override
    protected Duration doParse(String raw) {
        return Duration.parse(raw);
    }

    @Override
    protected void check(Duration value) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw invalid("duration must be between " + min + " and " + max);
        }
    }
}
