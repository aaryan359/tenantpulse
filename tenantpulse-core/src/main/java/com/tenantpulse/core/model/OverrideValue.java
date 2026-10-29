package com.tenantpulse.core.model;

/** A parsed override and the config version that last wrote it. */
public record OverrideValue(Object value, long version) {
}
