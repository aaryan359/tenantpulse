package com.tenantpulse.core.resolve;

import com.tenantpulse.core.model.OverrideValue;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeContext;
import com.tenantpulse.core.model.SettingDefinition;

import java.util.Map;

/**
 * Most-specific-wins resolution: SHIFT, then OFFICE, then CLIENT, then a GLOBAL override,
 * then the definition's default. Cost is at most four hash lookups, independent of how many
 * settings or overrides exist.
 */
public final class SettingResolver {

    public ResolvedSetting resolve(SettingDefinition definition, Map<Scope, OverrideValue> overridesForKey,
                                   ScopeContext context) {
        if (overridesForKey != null && !overridesForKey.isEmpty()) {
            for (Scope scope : context.chain()) {
                OverrideValue hit = overridesForKey.get(scope);
                if (hit != null) {
                    return new ResolvedSetting(definition.key(), hit.value(), scope.level(), scope,
                            hit.version(), false);
                }
            }
        }
        return new ResolvedSetting(definition.key(), definition.defaultValue(),
                Scope.global().level(), Scope.global(), 0L, true);
    }
}
