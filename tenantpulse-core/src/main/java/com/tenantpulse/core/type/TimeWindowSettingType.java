package com.tenantpulse.core.type;

import java.time.LocalTime;
import java.util.Map;

/**
 * A daily window written {@code HH:mm-HH:mm}. A window whose end is not after its start is
 * rejected, unless the setting is declared to allow overnight windows (a night-safety window
 * of 22:00-06:00 is legitimate). A window with start equal to end is always rejected.
 */
public final class TimeWindowSettingType extends SettingValueType<TimeWindow> {

    private final boolean allowOvernight;

    public TimeWindowSettingType(boolean allowOvernight) {
        super(TimeWindow.class);
        this.allowOvernight = allowOvernight;
    }

    @Override
    public String name() {
        return "time window";
    }

    @Override
    public String kind() {
        return "time_window";
    }

    @Override
    public Map<String, String> params() {
        return Map.of("allowOvernight", Boolean.toString(allowOvernight));
    }

    @Override
    public String format(TimeWindow value) {
        return value.start() + "-" + value.end();
    }

    @Override
    protected TimeWindow doParse(String raw) {
        String[] parts = raw.split("-", -1);
        if (parts.length != 2) {
            throw invalid("time window must look like HH:mm-HH:mm");
        }
        return new TimeWindow(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()));
    }

    @Override
    protected void check(TimeWindow value) {
        if (value.start().equals(value.end())) {
            throw invalid("time window must not start and end at the same time");
        }
        if (value.isOvernight() && !allowOvernight) {
            throw invalid("time window must end after it starts");
        }
    }
}
