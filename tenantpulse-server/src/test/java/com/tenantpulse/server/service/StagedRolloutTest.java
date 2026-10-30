package com.tenantpulse.server.service;

import com.tenantpulse.server.api.ApiTestSupport;
import com.tenantpulse.server.security.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hard rule: a change can go to chosen offices first and be promoted to everyone later. */
class StagedRolloutTest extends ApiTestSupport {

    private static final String STAGE = "{\"clientId\":\"%s\",\"offices\":[\"blr\",\"pune\"],"
            + "\"changes\":[{\"key\":\"booking.cutoff.minutes\",\"value\":\"30\"}],\"reason\":\"pilot\"}";

    @Test
    void stagedValuesReachOnlyTheChosenOffices() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        String service = token("svc", Role.SERVICE, acme);

        send(operator, "/v1/rollouts", STAGE.formatted(acme)).andExpect(status().isOk());

        for (String office : new String[]{"blr", "pune"}) {
            read(service, "/v1/config/booking.cutoff.minutes?office=" + office)
                    .andExpect(jsonPath("$.value").value("30")).andExpect(jsonPath("$.level").value("OFFICE"));
        }
        read(service, "/v1/config/booking.cutoff.minutes?office=hyd")
                .andExpect(jsonPath("$.value").value("60")).andExpect(jsonPath("$.fromDefault").value(true));
    }

    @Test
    void promotionAppliesTheValueToEveryoneInOneStep() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        String service = token("svc", Role.SERVICE, acme);
        long rolloutId = body(send(operator, "/v1/rollouts", STAGE.formatted(acme))).get("rolloutId").asLong();

        long promotedAt = body(send(operator, "/v1/rollouts/" + rolloutId + "/promote", "{\"reason\":\"went well\"}"))
                .get("version").asLong();

        for (String office : new String[]{"blr", "pune", "hyd"}) {
            read(service, "/v1/config/booking.cutoff.minutes?office=" + office)
                    .andExpect(jsonPath("$.value").value("30")).andExpect(jsonPath("$.level").value("CLIENT"))
                    .andExpect(jsonPath("$.version").value(promotedAt));
        }
    }

    @Test
    void promotingTwiceReturnsTheFirstPromotion() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        long rolloutId = body(send(operator, "/v1/rollouts", STAGE.formatted(acme))).get("rolloutId").asLong();

        long first = body(send(operator, "/v1/rollouts/" + rolloutId + "/promote", "{\"reason\":\"a\"}")).get("version").asLong();
        long second = body(send(operator, "/v1/rollouts/" + rolloutId + "/promote", "{\"reason\":\"b\"}")).get("version").asLong();
        assertEquals(first, second);
    }

    @Test
    void aLaterEditToAStagedOfficeSurvivesPromotion() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        String service = token("svc", Role.SERVICE, acme);
        long rolloutId = body(send(operator, "/v1/rollouts", STAGE.formatted(acme))).get("rolloutId").asLong();

        publish(operator, setOne("booking.cutoff.minutes", "OFFICE", acme, "pune", "15", null)).andExpect(status().isOk());
        send(operator, "/v1/rollouts/" + rolloutId + "/promote", "{\"reason\":\"go\"}").andExpect(status().isOk());

        read(service, "/v1/config/booking.cutoff.minutes?office=pune")
                .andExpect(jsonPath("$.value").value("15")).andExpect(jsonPath("$.level").value("OFFICE"));
        read(service, "/v1/config/booking.cutoff.minutes?office=blr")
                .andExpect(jsonPath("$.value").value("30")).andExpect(jsonPath("$.level").value("CLIENT"));
    }

    @Test
    void anotherTenantCannotStageOrPromote() throws Exception {
        String acme = unique("acme");
        String acmeOperator = token("op", Role.OPERATOR, acme);
        String globexOperator = token("op-g", Role.OPERATOR, unique("globex"));
        long rolloutId = body(send(acmeOperator, "/v1/rollouts", STAGE.formatted(acme))).get("rolloutId").asLong();

        send(globexOperator, "/v1/rollouts", STAGE.formatted(acme)).andExpect(status().isForbidden());
        send(globexOperator, "/v1/rollouts/" + rolloutId + "/promote", "{\"reason\":\"x\"}").andExpect(status().isForbidden());
    }

    @Test
    void badInputIsRejected() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        send(operator, "/v1/rollouts", "{\"clientId\":\"" + acme + "\",\"offices\":[],\"changes\":[{\"key\":\"escort.rule\",\"value\":\"NONE\"}],\"reason\":\"x\"}")
                .andExpect(status().isBadRequest());
        send(operator, "/v1/rollouts", "{\"clientId\":\"" + acme + "\",\"offices\":[\"blr\"],\"changes\":[{\"key\":\"booking.cutoff.minutes\",\"value\":\"-30\"}],\"reason\":\"x\"}")
                .andExpect(status().isUnprocessableEntity());
        send(operator, "/v1/rollouts/999999/promote", "{\"reason\":\"x\"}").andExpect(status().isNotFound());
        long plain = body(publish(operator, setOne("escort.rule", "CLIENT", acme, null, "NONE", null))).get("version").asLong();
        send(operator, "/v1/rollouts/" + plain + "/promote", "{\"reason\":\"x\"}").andExpect(status().isBadRequest());
    }
}
