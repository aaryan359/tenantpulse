package com.tenantpulse.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tenantpulse.server.security.Role;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hard rule: one client can never read or change another client's data. */
class TenantIsolationTest extends ApiTestSupport {

    @Test
    void aTenantNeverSeesAnotherTenantsOverrides() throws Exception {
        String acme = unique("acme");
        String globex = unique("globex");
        String acmeOperator = token("op-a", Role.OPERATOR, acme);
        String globexService = token("svc-g", Role.SERVICE, globex);
        publish(acmeOperator, setOne("booking.cutoff.minutes", "OFFICE", acme, "blr", "30", null)).andExpect(status().isOk());

        read(globexService, "/v1/config/booking.cutoff.minutes?office=blr")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("60"))
                .andExpect(jsonPath("$.fromDefault").value(true));
    }

    @Test
    void snapshotAndChangesOfOneTenantContainNothingOfAnother() throws Exception {
        String acme = unique("acme");
        String globex = unique("globex");
        String acmeOperator = token("op-a", Role.OPERATOR, acme);
        String globexService = token("svc-g", Role.SERVICE, globex);
        publish(acmeOperator, setOne("escort.rule", "CLIENT", acme, null, "REQUIRED", null)).andExpect(status().isOk());

        read(globexService, "/v1/snapshot").andExpect(status().isOk()).andExpect(content().string(not(containsString(acme))));
        read(globexService, "/v1/changes?since=0").andExpect(status().isOk())
                .andExpect(content().string(not(containsString(acme))));
    }

    @Test
    void askingForAnotherTenantByNameIsRefused() throws Exception {
        String acme = unique("acme");
        String globexService = token("svc-g", Role.SERVICE, unique("globex"));
        for (String path : new String[]{"/v1/snapshot", "/v1/version", "/v1/changes?since=0", "/v1/config",
                "/v1/config/escort.rule", "/v1/stream"}) {
            String separator = path.contains("?") ? "&" : "?";
            read(globexService, path + separator + "tenant=" + acme).andExpect(status().isForbidden());
        }
    }

    @Test
    void anOperatorCannotChangeAnotherTenant() throws Exception {
        String acme = unique("acme");
        String globexOperator = token("op-g", Role.OPERATOR, unique("globex"));
        publish(globexOperator, setOne("escort.rule", "CLIENT", acme, null, "NONE", null))
                .andExpect(status().isForbidden());
        publish(globexOperator, setOne("escort.rule", "OFFICE", acme, "blr", "NONE", null))
                .andExpect(status().isForbidden());
    }

    @Test
    void aChangeSetMixingOwnAndForeignScopesIsRefusedAsAWhole() throws Exception {
        String acme = unique("acme");
        String globex = unique("globex");
        String acmeOperator = token("op-a", Role.OPERATOR, acme);
        String body = "{\"reason\":\"x\",\"changes\":["
                + "{\"key\":\"escort.rule\",\"scope\":{\"level\":\"CLIENT\",\"clientId\":\"" + acme + "\"},\"value\":\"NONE\"},"
                + "{\"key\":\"escort.rule\",\"scope\":{\"level\":\"CLIENT\",\"clientId\":\"" + globex + "\"},\"value\":\"NONE\"}]}";
        publish(acmeOperator, body).andExpect(status().isForbidden());
        // and nothing of it was applied to the tenant's own scope
        read(token("svc-a", Role.SERVICE, acme), "/v1/config/escort.rule")
                .andExpect(jsonPath("$.fromDefault").value(true));
    }

    @Test
    void onlyAnAdminMayChangePlatformWideSettings() throws Exception {
        String acme = unique("acme");
        String operator = token("op-a", Role.OPERATOR, acme);
        publish(operator, setOne("escort.rule", "GLOBAL", null, null, "NONE", null)).andExpect(status().isForbidden());
        publish(token("root", Role.ADMIN, null), setOne("night.safety.window", "GLOBAL", null, null, "21:00-05:00", null))
                .andExpect(status().isOk());
        // every tenant sees the platform-wide change
        read(token("svc", Role.SERVICE, acme), "/v1/config/night.safety.window")
                .andExpect(jsonPath("$.value").value("21:00-05:00"))
                .andExpect(jsonPath("$.level").value("GLOBAL"));
    }

    @Test
    void anAdminMustNameTheTenantItReads() throws Exception {
        String admin = token("root", Role.ADMIN, null);
        read(admin, "/v1/snapshot").andExpect(status().isBadRequest());
        read(admin, "/v1/snapshot?tenant=" + unique("acme")).andExpect(status().isOk());
    }

    @Test
    void anUnaffectedTenantHasNothingToFetchWhenSomeoneElseChanges() throws Exception {
        String acme = unique("acme");
        String globex = unique("globex");
        String globexService = token("svc-g", Role.SERVICE, globex);
        long before = body(read(globexService, "/v1/version")).get("relevant").asLong();
        publish(token("op-a", Role.OPERATOR, acme), setOne("escort.rule", "CLIENT", acme, null, "NONE", null))
                .andExpect(status().isOk());

        JsonNode after = body(read(globexService, "/v1/version"));
        assertEquals(before, after.get("relevant").asLong(), "globex has nothing new to fetch");
        assertTrue(after.get("latest").asLong() > after.get("relevant").asLong(),
                "the platform moved on, but not in a way that concerns globex");
    }
}
