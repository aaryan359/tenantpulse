package com.tenantpulse.core.snapshot;

import com.tenantpulse.core.exception.StaleVersionException;
import com.tenantpulse.core.exception.UnknownSettingException;
import com.tenantpulse.core.model.OverrideEntry;
import com.tenantpulse.core.model.OverrideValue;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeContext;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.resolve.ResolvedSetting;
import com.tenantpulse.core.resolve.SettingResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable view of all settings at one config version. Nothing in it ever changes after
 * construction, so any number of threads may read it without locks.
 *
 * <p>Updates are copy-on-write: {@link #apply} returns a <em>new</em> snapshot and leaves this
 * one untouched. A holder swaps one reference to publish it, which is what makes a change set
 * atomic: a reader holds either the old snapshot or the new one, never a blend. Only the
 * per-key maps touched by a change are copied; the rest are shared with the previous snapshot.
 *
 * <p>A reader that needs several settings to agree (a window's start and end) must read them
 * all from the same snapshot instance.
 */
public final class ConfigSnapshot {

    private static final SettingResolver RESOLVER = new SettingResolver();

    private final long version;
    private final Map<String, SettingDefinition> definitions;
    private final Map<String, Map<Scope, OverrideValue>> overrides;

    private ConfigSnapshot(long version, Map<String, SettingDefinition> definitions,
                           Map<String, Map<Scope, OverrideValue>> overrides) {
        this.version = version;
        this.definitions = definitions;
        this.overrides = overrides;
    }

    /** A snapshot at version 0 that knows the given definitions and has no overrides. */
    public static ConfigSnapshot empty(Collection<SettingDefinition> definitions) {
        Map<String, SettingDefinition> byKey = new HashMap<>();
        for (SettingDefinition definition : definitions) {
            if (byKey.put(definition.key(), definition) != null) {
                throw new IllegalArgumentException("duplicate definition for key '" + definition.key() + "'");
            }
        }
        return new ConfigSnapshot(0L, Map.copyOf(byKey), Map.of());
    }

    /**
     * Rebuilds a snapshot from stored overrides, keeping the version that wrote each one. Used when a
     * client loads a full snapshot from the server or from its disk copy.
     */
    public static ConfigSnapshot restore(long version, Collection<SettingDefinition> definitions,
                                         Collection<OverrideEntry> entries) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        ConfigSnapshot base = empty(definitions);
        Map<String, Map<Scope, OverrideValue>> grouped = new HashMap<>();
        for (OverrideEntry entry : entries) {
            SettingDefinition definition = base.definitions.get(entry.key());
            if (definition == null) {
                throw new UnknownSettingException(entry.key());
            }
            if (entry.version() > version) {
                throw new IllegalArgumentException("an override is newer than the snapshot version");
            }
            grouped.computeIfAbsent(entry.key(), k -> new HashMap<>())
                    .put(entry.scope(), new OverrideValue(definition.parse(entry.value()), entry.version()));
        }
        Map<String, Map<Scope, OverrideValue>> frozen = new HashMap<>();
        grouped.forEach((key, perKey) -> frozen.put(key, Map.copyOf(perKey)));
        return new ConfigSnapshot(version, base.definitions, Collections.unmodifiableMap(frozen));
    }

    public long version() {
        return version;
    }

    public Set<String> keys() {
        return definitions.keySet();
    }

    public Collection<SettingDefinition> definitions() {
        return definitions.values();
    }

    public SettingDefinition definition(String key) {
        SettingDefinition definition = definitions.get(key);
        if (definition == null) {
            throw new UnknownSettingException(key);
        }
        return definition;
    }

    /** The value that applies in {@code context}. */
    public Object get(String key, ScopeContext context) {
        return explain(key, context).value();
    }

    public <T> T get(String key, ScopeContext context, Class<T> type) {
        return type.cast(get(key, context));
    }

    /** The value that applies in {@code context}, and which level supplied it. */
    public ResolvedSetting explain(String key, ScopeContext context) {
        SettingDefinition definition = definitions.get(key);
        if (definition == null) {
            throw new UnknownSettingException(key);
        }
        return RESOLVER.resolve(definition, overrides.get(key), context);
    }

    /** Every override as text, in a stable order (by key, then scope). Used for the wire, the disk copy and diffs. */
    public List<OverrideEntry> overrideEntries() {
        List<OverrideEntry> entries = new ArrayList<>();
        overrides.forEach((key, perKey) -> {
            SettingDefinition definition = definitions.get(key);
            perKey.forEach((scope, override) ->
                    entries.add(new OverrideEntry(key, scope, definition.format(override.value()), override.version())));
        });
        entries.sort(Comparator.comparing(OverrideEntry::key).thenComparing(e -> e.scope().key()));
        return entries;
    }

    /**
     * Returns a new snapshot at {@code newVersion} with all {@code changes} applied together.
     * If any change is invalid the exception is thrown and no snapshot is produced, so a change
     * set is all-or-nothing. Versions only move forward: {@code newVersion} must exceed the
     * current one. An empty change list just advances the version.
     *
     * @throws StaleVersionException if {@code newVersion <= version()}
     * @throws UnknownSettingException if a change names a key with no definition
     * @throws com.tenantpulse.core.exception.InvalidSettingValueException if a value fails its type's rules
     */
    public ConfigSnapshot apply(long newVersion, Collection<SettingChange> changes) {
        if (newVersion <= version) {
            throw new StaleVersionException(version, newVersion);
        }
        Map<String, Map<Scope, OverrideValue>> next = new HashMap<>(overrides);
        Map<String, Map<Scope, OverrideValue>> edited = new HashMap<>();
        Set<String> touched = new HashSet<>();

        for (SettingChange change : changes) {
            SettingDefinition definition = definitions.get(change.key());
            if (definition == null) {
                throw new UnknownSettingException(change.key());
            }
            Map<Scope, OverrideValue> perKey = edited.computeIfAbsent(change.key(),
                    k -> new HashMap<>(overrides.getOrDefault(k, Map.of())));
            touched.add(change.key());
            if (change.isRemoval()) {
                perKey.remove(change.scope());
            } else {
                perKey.put(change.scope(), new OverrideValue(definition.parse(change.rawValue()), newVersion));
            }
        }

        for (String key : touched) {
            Map<Scope, OverrideValue> perKey = edited.get(key);
            if (perKey.isEmpty()) {
                next.remove(key);
            } else {
                next.put(key, Map.copyOf(perKey));
            }
        }
        return new ConfigSnapshot(newVersion, definitions, Collections.unmodifiableMap(next));
    }
}
