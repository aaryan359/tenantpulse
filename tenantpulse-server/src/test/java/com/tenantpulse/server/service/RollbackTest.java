package com.tenantpulse.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tenantpulse.server.api.ApiTestSupport;
import com.tenantpulse.server.security.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hard rule: roll back to any previous version in one call, with a diff of what changes. */
class RollbackTest extends ApiTestSupport {

    private long publishCutoff(String operator, String tenant, String level, String office, String value) throws Exception {
        return body(publish(operator, setOne("booking.cutoff.minutes", level, tenant, office, value, null)))
                .get("version").asLong();
    }

    @Test
    void rollsBackToAnEarlierVersionAndReportsTheDiff() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        String service = token("svc", Role.SERVICE, acme);

        long v1 = publishCutoff(operator, acme, "OFFICE", "blr", "30");
        publishCutoff(operator, acme, "OFFICE", "blr", "20");
        publishCutoff(operator, acme, "OFFICE", "pune", "10");

        JsonNode result = body(send(operator, "/v1/rollbacks",
                "{\"targetVersion\":" + v1 + ",\"reason\":\"undo\"}").andExpect(status().isOk()));

        assertTrue(result.get("newVersion").asLong() > v1);
        assertEquals(2, result.get("diff").size(), "blr goes back to 30 and pune's override is removed");
        read(service, "/v1/config/booking.cutoff.minutes?office=blr").andExpect(jsonPath("$.value").value("30"));
        read(service, "/v1/config/booking.cutoff.minutes?office=pune")
                .andExpect(jsonPath("$.value").value("60")).andExpect(jsonPath("$.fromDefault").value(true));
    }

    @Test
    void historyIsNotRewrittenTheRollbackIsANewVersion() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        long v1 = publishCutoff(operator, acme, "OFFICE", "blr", "30");
        long v2 = publishCutoff(operator, acme, "OFFICE", "blr", "20");

        long rolledBack = body(send(operator, "/v1/rollbacks", "{\"targetVersion\":" + v1 + ",\"reason\":\"undo\"}"))
                .get("newVersion").asLong();
        assertEquals(v2 + 1, rolledBack);
    }

    @Test
    void aDryRunShowsTheDiffAndChangesNothing() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        String service = token("svc", Role.SERVICE, acme);
        long v1 = publishCutoff(operator, acme, "OFFICE", "blr", "30");
        publishCutoff(operator, acme, "OFFICE", "blr", "20");
        long latest = body(read(operator, "/v1/version")).get("latest").asLong();

        send(operator, "/v1/rollbacks", "{\"targetVersion\":" + v1 + ",\"reason\":\"preview\",\"dryRun\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.newVersion").doesNotExist())
                .andExpect(jsonPath("$.diff[0].before").value("20"))
                .andExpect(jsonPath("$.diff[0].after").value("30"));

        assertEquals(latest, body(read(operator, "/v1/version")).get("latest").asLong());
        read(service, "/v1/config/booking.cutoff.minutes?office=blr").andExpect(jsonPath("$.value").value("20"));
    }

    @Test
    void rollingBackToTheStateThatAlreadyHoldsChangesNothing() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        long v1 = publishCutoff(operator, acme, "OFFICE", "blr", "30");
        // an unrelated change in between does not matter to this tenant's overrides
        publishCutoff(operator, acme, "OFFICE", "blr", "30");

        send(operator, "/v1/rollbacks", "{\"targetVersion\":" + v1 + ",\"reason\":\"noop\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.diff").isEmpty());
    }

    @Test
    void anOperatorsRollbackNeverTouchesAnotherTenant() throws Exception {
        String acme = unique("acme");
        String globex = unique("globex");
        String acmeOperator = token("op-a", Role.OPERATOR, acme);
        String globexOperator = token("op-g", Role.OPERATOR, globex);
        String globexService = token("svc-g", Role.SERVICE, globex);

        long marker = publishCutoff(acmeOperator, acme, "OFFICE", "blr", "30");
        publishCutoff(globexOperator, globex, "OFFICE", "blr", "20");

        // acme rolls back to a point before globex's change: globex must be untouched
        send(acmeOperator, "/v1/rollbacks", "{\"targetVersion\":" + (marker - 1) + ",\"reason\":\"undo\"}")
                .andExpect(status().isOk());
        read(globexService, "/v1/config/booking.cutoff.minutes?office=blr").andExpect(jsonPath("$.value").value("20"));
    }

    @Test
    void anAdminCanRollBackOneNamedClient() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        String service = token("svc", Role.SERVICE, acme);
        long before = body(read(operator, "/v1/version")).get("latest").asLong();
        publishCutoff(operator, acme, "OFFICE", "blr", "30");

        send(token("root", Role.ADMIN, null), "/v1/rollbacks",
                "{\"targetVersion\":" + before + ",\"clientId\":\"" + acme + "\",\"reason\":\"admin undo\"}")
                .andExpect(status().isOk());
        read(service, "/v1/config/booking.cutoff.minutes?office=blr").andExpect(jsonPath("$.fromDefault").value(true));
    }

    @Test
    void invalidTargetsAreRejected() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        publishCutoff(operator, acme, "OFFICE", "blr", "30");
        long latest = body(read(operator, "/v1/version")).get("latest").asLong();

        send(operator, "/v1/rollbacks", "{\"targetVersion\":" + latest + ",\"reason\":\"x\"}").andExpect(status().isBadRequest());
        send(operator, "/v1/rollbacks", "{\"targetVersion\":" + (latest + 5) + ",\"reason\":\"x\"}").andExpect(status().isBadRequest());
        send(operator, "/v1/rollbacks", "{\"targetVersion\":-1,\"reason\":\"x\"}").andExpect(status().isBadRequest());
    }

    @Test
    void aReadOnlyRoleCannotRollBack() throws Exception {
        String acme = unique("acme");
        send(token("svc", Role.SERVICE, acme), "/v1/rollbacks", "{\"targetVersion\":0,\"reason\":\"x\"}")
                .andExpect(status().isForbidden());
    }
}
