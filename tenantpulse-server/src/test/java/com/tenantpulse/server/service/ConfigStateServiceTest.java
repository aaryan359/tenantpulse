package com.tenantpulse.server.service;

import com.tenantpulse.core.model.ChangeSet;
import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.model.ScopeContext;
import com.tenantpulse.core.model.SettingChange;
import com.tenantpulse.server.observability.TenantPulseMetrics;
import com.tenantpulse.server.repository.InMemoryChangeSetRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigStateServiceTest {

    private InMemoryChangeSetRepository repository;
    private ConfigStateService state;

    @BeforeEach
    void setUp() {
        repository = new InMemoryChangeSetRepository();
        state = new ConfigStateService(repository, new SettingCatalog(), new TenantPulseMetrics(new SimpleMeterRegistry()));
    }

    private long publish(Scope scope, String value) {
        return repository.publish(new ChangeSet("t", "r", null,
                List.of(SettingChange.set("booking.cutoff.minutes", scope, value)))).version();
    }

    @Test
    void refreshAppliesEverythingPublishedInOrder() {
        publish(Scope.client("acme"), "45");
        long v2 = publish(Scope.office("acme", "blr"), "30");

        assertEquals(2, state.refresh().size());
        assertEquals(v2, state.snapshot().version());
        assertEquals(30L, state.snapshot().get("booking.cutoff.minutes", ScopeContext.office("acme", "blr"), Long.class));
        assertEquals(0, state.refresh().size(), "nothing new the second time");
    }

    @Test
    void relevantVersionIsPerTenantAndGlobalChangesReachEveryone() {
        long acme = publish(Scope.client("acme"), "45");
        state.refresh();
        assertEquals(acme, state.relevantVersion("acme"));
        assertEquals(0L, state.relevantVersion("globex"));

        long global = publish(Scope.global(), "50");
        state.refresh();
        assertEquals(global, state.relevantVersion("globex"));
        assertEquals(global, state.relevantVersion("acme"));
    }

    @Test
    void affectedTenantsNamesTheClientsOrEveryoneForGlobalChanges() {
        ChangeSet two = new ChangeSet("t", "r", null, List.of(
                SettingChange.set("booking.cutoff.minutes", Scope.client("a"), "1"),
                SettingChange.set("booking.cutoff.minutes", Scope.office("b", "o"), "2")));
        assertEquals(Set.of("a", "b"), ConfigStateService.affectedTenants(two));

        ChangeSet withGlobal = new ChangeSet("t", "r", null, List.of(
                SettingChange.set("booking.cutoff.minutes", Scope.client("a"), "1"),
                SettingChange.set("booking.cutoff.minutes", Scope.global(), "2")));
        assertNull(ConfigStateService.affectedTenants(withGlobal));
    }

    @Test
    void stateAtReplaysTheLogUpToAVersion() {
        long v1 = publish(Scope.client("acme"), "45");
        publish(Scope.client("acme"), "40");
        state.refresh();

        assertEquals(45L, state.stateAt(v1).get("booking.cutoff.minutes", ScopeContext.client("acme"), Long.class));
        assertEquals(60L, state.stateAt(0).get("booking.cutoff.minutes", ScopeContext.client("acme"), Long.class));
        assertEquals(40L, state.snapshot().get("booking.cutoff.minutes", ScopeContext.client("acme"), Long.class));
    }

    @Test
    void aFreshServerRebuildsItsStateFromStorage() {
        publish(Scope.office("acme", "blr"), "30");
        ConfigStateService restarted = new ConfigStateService(repository, new SettingCatalog(),
                new TenantPulseMetrics(new SimpleMeterRegistry()));
        restarted.loadFromStorage();
        assertEquals(30L, restarted.snapshot().get("booking.cutoff.minutes", ScopeContext.office("acme", "blr"), Long.class));
        assertEquals(1L, restarted.snapshot().version());
    }
}
