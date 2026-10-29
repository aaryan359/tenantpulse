package com.tenantpulse.core.type;

import java.time.LocalTime;
import java.util.Objects;

/** A daily window from {@code start} (inclusive) to {@code end} (exclusive); may wrap past midnight. */
public record TimeWindow(LocalTime start, LocalTime end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }

    public boolean isOvernight() {
        return end.isBefore(start);
    }

    public boolean contains(LocalTime time) {
        if (isOvernight()) {
            return !time.isBefore(start) || time.isBefore(end);
        }
        return !time.isBefore(start) && time.isBefore(end);
    }
}
