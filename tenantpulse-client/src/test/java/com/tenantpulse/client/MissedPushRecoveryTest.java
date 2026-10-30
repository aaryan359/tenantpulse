package com.tenantpulse.client;

import com.tenantpulse.core.model.Scope;
import com.tenantpulse.core.type.NumberSettingType;
import com.tenantpulse.core.wire.Wire;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.tenantpulse.client.FakeConfigServer.CUTOFF;
import static com.tenantpulse.client.TestSupport.eventually;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Hard rule: the periodic version check heals any missed push, so staleness stays bounded. */
class MissedPushRecoveryTest {

    @Test
    void aChangeIsPickedUpEvenIfThePushNeverArrives() {
        FakeConfigServer server = new FakeConfigServer();
        server.publishCutoff("blr", 60);
        try (TenantPulseClient client = TenantPulseClient.create(TestSupport.config().build(), server)) {
            client.start();
            assertEquals(60L, client.getLong(CUTOFF, "blr"));

            server.publishCutoff("blr", 30);   // note: no push is sent

            eventually(Duration.ofSeconds(2), "the version check finds the change",
                    () -> client.getLong(CUTOFF, "blr") == 30L);
            assertEquals(0L, client.metrics().pushesReceived());
        }
    }

    @Test
    void recoversFromTransientFailuresOnItsOwn() {
        FakeConfigServer server = new FakeConfigServer();
        server.publishCutoff("blr", 60);
        try (TenantPulseClient client = TenantPulseClient.create(TestSupport.config().build(), server)) {
            client.start();
            server.failNextChangeCalls(5);     // failure injection: the next five fetches fail
            server.publishCutoff("blr", 30);

            eventually(Duration.ofSeconds(5), "client converges despite failures",
                    () -> client.getLong(CUTOFF, "blr") == 30L);
            assertEquals(30L, client.getLong(CUTOFF, "blr"));
            org.junit.jupiter.api.Assertions.assertTrue(client.metrics().syncFailures() >= 1,
                    "the failures must be counted");
        }
    }

    @Test
    void aNewlyDefinedSettingTriggersAFullSnapshotInsteadOfBreakingTheClient() {
        FakeConfigServer server = new FakeConfigServer();
        server.publishCutoff("blr", 60);
        try (TenantPulseClient client = TenantPulseClient.create(TestSupport.config().build(), server)) {
            client.start();
            NumberSettingType type = new NumberSettingType(0, 100);
            server.addDefinition(new Wire.Definition("escort.count", type.kind(), type.params(), "1", "new setting"));
            server.publish(new Wire.Change("escort.count", Scope.client("acme"), "3"));

            eventually(Duration.ofSeconds(3), "client learns the new setting",
                    () -> client.metrics().fullSnapshotsLoaded() >= 2);
            assertEquals(3L, client.get("escort.count", null, null, Long.class));
        }
    }
}
