package com.tenantpulse.server.service;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeLevel;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.core.snapshot.ConfigSnapshot;
import com.tenantpulse.server.observability.TenantPulseMetrics;
import com.tenantpulse.server.repository.ChangeSetRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The server's own in-memory view of the current configuration: the same immutable {@link ConfigSnapshot}
 * the clients use, rebuilt from the database at start-up and kept current by {@link #refresh()}.
 *
 * <p>It also remembers, per tenant, the newest version that touched that tenant. That lets the server tell
 * an unaffected tenant "nothing for you" without any database work.
 */
@Service
public class ConfigStateService {

    private static final Logger log = LoggerFactory.getLogger(ConfigStateService.class);
    private static final int PAGE_SIZE = 500;

    private final ChangeSetRepository repository;
    private final SettingCatalog catalog;
    private final TenantPulseMetrics metrics;

    private final Map<String, Long> lastTouchedByTenant = new ConcurrentHashMap<>();
    private volatile long lastTouchedGlobally = 0L;
    private volatile ConfigSnapshot current;
    private final ReentrantLock refreshLock = new ReentrantLock();

    public ConfigStateService(ChangeSetRepository repository, SettingCatalog catalog, TenantPulseMetrics metrics) {
        this.repository = repository;
        this.catalog = catalog;
        this.metrics = metrics;
        this.current = ConfigSnapshot.empty(catalog.definitions());
    }

    @PostConstruct
    void loadFromStorage() {
        refresh();
        log.info("loaded configuration state, version={}", current.version());
    }

    /** The current snapshot of all tenants. Never null; safe to read from any thread. */
    public ConfigSnapshot snapshot() {
        return current;
    }

    /** The newest version that touched {@code tenantId} or the whole platform. */
    public long relevantVersion(String tenantId) {
        return Math.max(lastTouchedByTenant.getOrDefault(tenantId, 0L), lastTouchedGlobally);
    }

    /**
     * Applies every change set published since the version this server holds, in order, and returns them.
     * Safe to call at any time and from several threads; it is the only place the snapshot is replaced.
     */
    public List<PublishedChangeSet> refresh() {
        // A lock rather than synchronized: the repository call below does database I/O.
        refreshLock.lock();
        try {
            List<PublishedChangeSet> applied = new ArrayList<>();
            while (true) {
                List<PublishedChangeSet> page = repository.findSince(current.version(), PAGE_SIZE);
                if (page.isEmpty()) {
                    break;
                }
                ConfigSnapshot next = current;
                for (PublishedChangeSet published : page) {
                    next = next.apply(published.version(), published.changeSet().changes());
                    record(published);
                    applied.add(published);
                }
                current = next;
            }
            metrics.currentVersion(current.version());
            return applied;
        } finally {
            refreshLock.unlock();
        }
    }

    /** Rebuilds the configuration as it was at {@code version} by replaying the log. Version 0 is the empty state. */
    public ConfigSnapshot stateAt(long version) {
        ConfigSnapshot state = ConfigSnapshot.empty(catalog.definitions());
        while (state.version() < version) {
            List<PublishedChangeSet> page = repository.findSince(state.version(), PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (PublishedChangeSet published : page) {
                if (published.version() > version) {
                    return state;
                }
                state = state.apply(published.version(), published.changeSet().changes());
            }
        }
        return state;
    }

    /**
     * The tenants a change set touches, or {@code null} when it touches the whole platform (a GLOBAL scope),
     * which affects everyone.
     */
    public static Set<String> affectedTenants(ChangeSet changeSet) {
        Set<String> tenants = new HashSet<>();
        for (SettingChange change : changeSet.changes()) {
            Scope scope = change.scope();
            if (scope.level() == ScopeLevel.GLOBAL) {
                return null;
            }
            tenants.add(scope.clientId());
        }
        return tenants;
    }

    private void record(PublishedChangeSet published) {
        Set<String> tenants = affectedTenants(published.changeSet());
        if (tenants == null) {
            lastTouchedGlobally = published.version();
        } else {
            for (String tenant : tenants) {
                lastTouchedByTenant.put(tenant, published.version());
            }
        }
    }
}
