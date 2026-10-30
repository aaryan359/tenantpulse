package com.tenantpulse.client;

import com.tenantpulse.core.model.ScopeContext;
import com.tenantpulse.core.resolve.ResolvedSetting;
import com.tenantpulse.core.snapshot.ConfigSnapshot;
import com.tenantpulse.core.type.TimeWindow;

import java.time.Duration;
import java.util.List;

/**
 * A fixed view of the configuration at one version. Every read through the same view agrees with every other, so a
 * caller that needs several settings to be consistent (a night window's start and end) takes one view and reads
 * them all from it. Reads touch only memory.
 */
public final class ConfigView {

    private final ConfigSnapshot snapshot;
    private final String tenantId;

    ConfigView(ConfigSnapshot snapshot, String tenantId) {
        this.snapshot = snapshot;
        this.tenantId = tenantId;
    }

    /** The config version this view was taken at. */
    public long version() {
        return snapshot.version();
    }

    public <T> T get(String key, String officeId, String shiftId, Class<T> type) {
        return snapshot.get(key, new ScopeContext(tenantId, officeId, shiftId), type);
    }

    /** The value for the whole tenant, ignoring office and shift overrides. */
    public <T> T get(String key, Class<T> type) {
        return get(key, null, null, type);
    }

    public long getLong(String key, String officeId) {
        return get(key, officeId, null, Long.class);
    }

    public Duration getDuration(String key, String officeId) {
        return get(key, officeId, null, Duration.class);
    }

    public TimeWindow getTimeWindow(String key, String officeId) {
        return get(key, officeId, null, TimeWindow.class);
    }

    public String getString(String key, String officeId) {
        return get(key, officeId, null, String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> getList(String key, String officeId) {
        return (List<String>) get(key, officeId, null, List.class);
    }

    /** The value and which level (global, client, office, shift) supplied it. */
    public ResolvedSetting explain(String key, String officeId, String shiftId) {
        return snapshot.explain(key, new ScopeContext(tenantId, officeId, shiftId));
    }
}
