package com.tenantpulse.core.wire;

import com.tenantpulse.core.model.OverrideEntry;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.SettingDefinition;
import com.tenantpulse.core.type.SettingTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The JSON shapes shared by the server and the client library. Plain records, so the protocol is
 * defined in one place and both sides stay in step.
 */
public final class Wire {

    private Wire() {
    }

    /** A setting definition in a form a client can rebuild into a {@link SettingDefinition}. */
    public record Definition(String key, String kind, Map<String, String> params, String defaultValue,
                             String description) {

        public static Definition from(SettingDefinition definition) {
            return new Definition(definition.key(), definition.type().kind(), definition.type().params(),
                    definition.format(definition.defaultValue()), definition.description());
        }

        public SettingDefinition toDefinition() {
            return SettingDefinition.of(key, SettingTypes.create(kind, params), defaultValue, description);
        }
    }

    /** A single edit; a null value removes the override. */
    public record Change(String key, Scope scope, String value) {
    }

    /** A published change set as clients see it. */
    public record ChangeSetView(long version, String author, String reason, String kind, Instant publishedAt,
                                List<Change> changes) {
    }

    /** Everything a client needs to start: definitions plus the overrides that apply to its tenant. */
    public record SnapshotResponse(long version, List<Definition> definitions, List<OverrideEntry> overrides) {
    }

    /**
     * What changed after {@code fromVersion}, up to and including {@code toVersion}. When
     * {@code fullSnapshotRequired} is true the client is too far behind and must fetch the snapshot instead.
     */
    public record ChangesResponse(long fromVersion, long toVersion, boolean fullSnapshotRequired,
                                  List<ChangeSetView> changeSets) {
    }

    /**
     * The cheap version check. {@code latest} is the newest version; {@code relevant} is the newest version
     * that touched this tenant, so an unaffected tenant has nothing to fetch.
     */
    public record VersionResponse(long latest, long relevant) {
    }
}
