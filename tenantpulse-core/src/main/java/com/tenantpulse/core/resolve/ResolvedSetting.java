package com.tenantpulse.core.resolve;

import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeLevel;

/**
 * The answer to a read plus the "explain" detail: which level supplied the value, from which
 * scope, and at what config version ({@code 0} and {@code fromDefault} for the definition default).
 */
public record ResolvedSetting(String key, Object value, ScopeLevel level, Scope scope, long version,
                              boolean fromDefault) {
}
