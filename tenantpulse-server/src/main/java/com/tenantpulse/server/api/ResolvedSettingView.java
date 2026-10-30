package com.tenantpulse.server.api;

import com.tenantpulse.core.model.ScopeLevel;
import com.tenantpulse.core.resolve.ResolvedSetting;

/** A resolved value as returned by the API: the value as text and which level supplied it (explain mode). */
public record ResolvedSettingView(String key, String value, ScopeLevel level, String scope, long version,
                                  boolean fromDefault) {

    public static ResolvedSettingView of(ResolvedSetting resolved, String formattedValue) {
        return new ResolvedSettingView(resolved.key(), formattedValue, resolved.level(), resolved.scope().key(),
                resolved.version(), resolved.fromDefault());
    }
}
