package com.tenantpulse.server.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tenantpulse.core.model.OverrideEntry;
import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeContext;
import com.tenantpulse.core.model.ScopeLevel;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.resolve.ResolvedSetting;
import com.tenantpulse.core.snapshot.ConfigSnapshot;
import com.tenantpulse.core.wire.Wire;
import com.tenantpulse.server.config.TenantPulseProperties;
import com.tenantpulse.server.repository.ChangeSetRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers client reads, always limited to what the caller's tenant may see: platform-wide (GLOBAL) settings plus
 * that tenant's own overrides. Nothing about any other tenant ever leaves this class.
 *
 * <p><b>Caching.</b> Snapshot and delta answers are cached per tenant and keyed by version. A new version
 * is a new key, so a cached answer is never wrong, only unused; old entries are evicted by size (least
 * recently used) and age. Nothing here can be stale beyond the latest version the server itself holds.
 */
@Service
public class ConfigReadService {

    private final ConfigStateService state;
    private final ChangeSetRepository repository;
    private final int maxChangeSets;

    private final Cache<String, Wire.SnapshotResponse> snapshots = Caffeine.newBuilder()
            .maximumSize(5_000).expireAfterAccess(Duration.ofMinutes(10)).build();
    private final Cache<String, Wire.ChangesResponse> deltas = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterWrite(Duration.ofMinutes(2)).build();

    public ConfigReadService(ConfigStateService state, ChangeSetRepository repository,
                             TenantPulseProperties properties) {
        this.state = state;
        this.repository = repository;
        this.maxChangeSets = properties.changes().maxChangeSets();
    }

    /** The cheap check clients run every couple of seconds. */
    public Wire.VersionResponse version(String tenantId) {
        return new Wire.VersionResponse(state.snapshot().version(), state.relevantVersion(tenantId));
    }

    public Wire.SnapshotResponse snapshotFor(String tenantId) {
        ConfigSnapshot snapshot = state.snapshot();
        return snapshots.get(tenantId + "@" + snapshot.version(), key -> buildSnapshot(snapshot, tenantId));
    }

    public Wire.ChangesResponse changesSince(String tenantId, long since) {
        if (since < 0) {
            throw new InvalidRequestException("since must not be negative");
        }
        long upTo = state.snapshot().version();
        if (since > upTo) {
            // The client is ahead of this server (for example storage was restored): make it start over.
            return new Wire.ChangesResponse(since, upTo, true, List.of());
        }
        if (since == upTo) {
            return new Wire.ChangesResponse(since, upTo, false, List.of());
        }
        return deltas.get(tenantId + "@" + since + "@" + upTo, key -> buildChanges(tenantId, since, upTo));
    }

    public ResolvedSetting explain(String tenantId, String key, String officeId, String shiftId) {
        return state.snapshot().explain(key, context(tenantId, officeId, shiftId));
    }

    /** The canonical text form of a resolved value, for API responses. */
    public String format(String key, Object value) {
        return state.snapshot().definition(key).format(value);
    }

    public List<ResolvedSetting> resolveAll(String tenantId, String officeId, String shiftId) {
        ConfigSnapshot snapshot = state.snapshot();
        ScopeContext context = context(tenantId, officeId, shiftId);
        return snapshot.keys().stream().sorted().map(key -> snapshot.explain(key, context)).toList();
    }

    private static ScopeContext context(String tenantId, String officeId, String shiftId) {
        try {
            return new ScopeContext(tenantId, blankToNull(officeId), blankToNull(shiftId));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Wire.SnapshotResponse buildSnapshot(ConfigSnapshot snapshot, String tenantId) {
        List<Wire.Definition> definitions = snapshot.definitions().stream()
                .map(Wire.Definition::from).sorted(java.util.Comparator.comparing(Wire.Definition::key)).toList();
        List<OverrideEntry> overrides = snapshot.overrideEntries().stream()
                .filter(entry -> visibleTo(entry.scope(), tenantId)).toList();
        return new Wire.SnapshotResponse(snapshot.version(), definitions, overrides);
    }

    private Wire.ChangesResponse buildChanges(String tenantId, long since, long upTo) {
        List<PublishedChangeSet> page = repository.findSince(since, maxChangeSets + 1);
        if (page.size() > maxChangeSets) {
            return new Wire.ChangesResponse(since, upTo, true, List.of());
        }
        List<Wire.ChangeSetView> views = new ArrayList<>();
        for (PublishedChangeSet published : page) {
            if (published.version() > upTo) {
                break;
            }
            List<Wire.Change> visible = new ArrayList<>();
            for (SettingChange change : published.changeSet().changes()) {
                if (visibleTo(change.scope(), tenantId)) {
                    visible.add(new Wire.Change(change.key(), change.scope(), change.rawValue()));
                }
            }
            if (!visible.isEmpty()) {
                views.add(new Wire.ChangeSetView(published.version(), published.changeSet().author(),
                        published.changeSet().reason(), published.changeSet().kind().name(),
                        published.publishedAt(), visible));
            }
        }
        return new Wire.ChangesResponse(since, upTo, false, views);
    }

    private static boolean visibleTo(Scope scope, String tenantId) {
        return scope.level() == ScopeLevel.GLOBAL || tenantId.equals(scope.clientId());
    }
}
