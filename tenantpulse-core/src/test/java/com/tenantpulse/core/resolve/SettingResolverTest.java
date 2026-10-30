package com.tenantpulse.core.resolve;

import com.tenantpulse.core.model.OverrideValue;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeContext;
import com.tenantpulse.core.model.ScopeLevel;
import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.type.NumberSettingType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingResolverTest {

    private final SettingResolver resolver = new SettingResolver();
    private final SettingDefinition cutoff = SettingDefinition.of(
            "booking.cutoff.minutes", new NumberSettingType(0, 1440), "60", "booking cut-off");

    private final Map<Scope, OverrideValue> overrides = new HashMap<>();

    @Test
    void fallsBackToDefaultWhenNothingOverrides() {
        ResolvedSetting r = resolver.resolve(cutoff, overrides, ScopeContext.office("acme", "blr"));
        assertEquals(60L, r.value());
        assertTrue(r.fromDefault());
        assertEquals(ScopeLevel.GLOBAL, r.level());
    }

    @Test
    void clientOverrideBeatsDefault() {
        overrides.put(Scope.client("acme"), new OverrideValue(45L, 3));
        ResolvedSetting r = resolver.resolve(cutoff, overrides, ScopeContext.office("acme", "blr"));
        assertEquals(45L, r.value());
        assertEquals(ScopeLevel.CLIENT, r.level());
        assertEquals(3L, r.version());
        assertFalse(r.fromDefault());
    }

    @Test
    void mostSpecificLevelWins() {
        overrides.put(Scope.global(), new OverrideValue(90L, 1));
        overrides.put(Scope.client("acme"), new OverrideValue(45L, 2));
        overrides.put(Scope.office("acme", "blr"), new OverrideValue(30L, 3));
        overrides.put(Scope.shift("acme", "blr", "night"), new OverrideValue(15L, 4));

        assertEquals(15L, value(ScopeContext.shift("acme", "blr", "night")));
        assertEquals(30L, value(ScopeContext.shift("acme", "blr", "day")));
        assertEquals(30L, value(ScopeContext.office("acme", "blr")));
        assertEquals(45L, value(ScopeContext.office("acme", "pune")));
        assertEquals(90L, value(ScopeContext.client("other")));
        assertEquals(90L, value(ScopeContext.global()));
    }

    @Test
    void explainNamesTheScopeThatSuppliedTheValue() {
        overrides.put(Scope.office("acme", "blr"), new OverrideValue(30L, 7));
        ResolvedSetting r = resolver.resolve(cutoff, overrides, ScopeContext.shift("acme", "blr", "day"));
        assertEquals(ScopeLevel.OFFICE, r.level());
        assertEquals(Scope.office("acme", "blr"), r.scope());
        assertEquals(7L, r.version());
    }

    @Test
    void anotherClientsOverrideNeverApplies() {
        overrides.put(Scope.client("globex"), new OverrideValue(5L, 2));
        overrides.put(Scope.office("globex", "blr"), new OverrideValue(5L, 3));
        assertEquals(60L, value(ScopeContext.office("acme", "blr")));
    }

    @Test
    void sameOfficeNameInAnotherClientDoesNotLeak() {
        overrides.put(Scope.office("globex", "blr"), new OverrideValue(5L, 3));
        assertEquals(60L, value(ScopeContext.office("acme", "blr")));
    }

    @Test
    void scopeShapesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new Scope(ScopeLevel.OFFICE, "acme", null, null));
        assertThrows(IllegalArgumentException.class, () -> new Scope(ScopeLevel.GLOBAL, "acme", null, null));
        assertThrows(IllegalArgumentException.class, () -> new ScopeContext(null, "blr", null));
        assertThrows(IllegalArgumentException.class, () -> new ScopeContext("acme", null, "night"));
    }

    private Object value(ScopeContext context) {
        return resolver.resolve(cutoff, overrides, context).value();
    }
}
