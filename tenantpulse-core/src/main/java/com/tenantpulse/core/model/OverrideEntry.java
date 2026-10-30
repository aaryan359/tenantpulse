package com.tenantpulse.core.model;

/** One override in text form, as stored and sent over the wire: where, what value, and which version wrote it. */
public record OverrideEntry(String key, Scope scope, String value, long version) {
}
