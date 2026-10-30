package com.tenantpulse.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tenantpulse.server.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Publishing: versions, validation (bad values are rejected before they are published), idempotency, clean errors. */
@ExtendWith(OutputCaptureExtension.class)
class ChangeSetApiTest extends ApiTestSupport {

    @Test
    void theExampleFromTheCaseStudy() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        String service = token("svc", Role.SERVICE, acme);

        publish(operator, setOne("booking.cutoff.minutes", "OFFICE", acme, "blr", "30", null)).andExpect(status().isOk());

        read(service, "/v1/config/booking.cutoff.minutes?office=blr")
                .andExpect(jsonPath("$.value").value("30")).andExpect(jsonPath("$.level").value("OFFICE"));
        read(service, "/v1/config/booking.cutoff.minutes?office=pune")
                .andExpect(jsonPath("$.value").value("60")).andExpect(jsonPath("$.fromDefault").value(true));
    }

    @Test
    void versionsIncreaseWithEveryPublish() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        long first = body(publish(operator, setOne("escort.rule", "CLIENT", acme, null, "NONE", null))).get("version").asLong();
        long second = body(publish(operator, setOne("escort.rule", "CLIENT", acme, null, "REQUIRED", null))).get("version").asLong();
        assertEquals(first + 1, second);
    }

    @Test
    void aRepeatedRequestWithTheSameKeyDoesNotCreateASecondVersion() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        String request = setOne("escort.rule", "CLIENT", acme, null, "NONE", unique("key"));

        long first = body(publish(operator, request)).get("version").asLong();
        long again = body(publish(operator, request)).get("version").asLong();
        long other = body(publish(operator, setOne("escort.rule", "CLIENT", acme, null, "REQUIRED", unique("key"))))
                .get("version").asLong();

        assertEquals(first, again);
        assertEquals(first + 1, other, "no version was used up by the repeat");
    }

    @Test
    void aNegativeCutoffIsRejectedBeforeItIsPublished() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        long latestBefore = body(read(operator, "/v1/version")).get("latest").asLong();

        publish(operator, setOne("booking.cutoff.minutes", "CLIENT", acme, null, "-30", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("change_set_rejected"))
                .andExpect(jsonPath("$.details[0].key").value("booking.cutoff.minutes"));

        assertEquals(latestBefore, body(read(operator, "/v1/version")).get("latest").asLong(),
                "a rejected change set must not use a version");
    }

    @Test
    void aWindowThatEndsBeforeItStartsIsRejected() throws Exception {
        String acme = unique("acme");
        // escort window type of "night.safety.window" allows overnight; a same-time window is still refused
        publish(token("op", Role.OPERATOR, acme), setOne("night.safety.window", "CLIENT", acme, null, "10:00-10:00", null))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aChangeSetWithOneBadValueAppliesNothing() throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        String request = "{\"reason\":\"x\",\"changes\":["
                + "{\"key\":\"escort.rule\",\"scope\":{\"level\":\"CLIENT\",\"clientId\":\"" + acme + "\"},\"value\":\"NONE\"},"
                + "{\"key\":\"booking.cutoff.minutes\",\"scope\":{\"level\":\"CLIENT\",\"clientId\":\"" + acme + "\"},\"value\":\"-5\"}]}";
        publish(operator, request).andExpect(status().isUnprocessableEntity());
        read(token("svc", Role.SERVICE, acme), "/v1/config/escort.rule").andExpect(jsonPath("$.fromDefault").value(true));
    }

    @Test
    void theAuthorIsTheCallerNotWhatTheRequestClaims() throws Exception {
        String acme = unique("acme");
        String operator = token("real-operator", Role.OPERATOR, acme);
        String forged = "{\"author\":\"someone-else\",\"reason\":\"x\",\"changes\":[{\"key\":\"escort.rule\","
                + "\"scope\":{\"level\":\"CLIENT\",\"clientId\":\"" + acme + "\"},\"value\":\"NONE\"}]}";
        publish(operator, forged).andExpect(status().isOk());

        JsonNode changes = body(read(token("svc", Role.SERVICE, acme), "/v1/changes?since=0"));
        JsonNode mine = changes.get("changeSets").get(changes.get("changeSets").size() - 1);
        assertEquals("real-operator", mine.get("author").asText());
    }

    @Test
    void malformedRequestsGetACleanErrorThatEchoesNothing() throws Exception {
        String operator = token("op", Role.OPERATOR, unique("acme"));
        mvc.perform(post("/v1/change-sets").header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json SECRET-PAYLOAD-123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("request body is missing or malformed"));
        publish(operator, "{\"reason\":\"x\",\"changes\":[{\"key\":\"escort.rule\",\"scope\":{\"level\":\"OFFICE\"},\"value\":\"A\"}]}")
                .andExpect(status().isBadRequest());
        publish(operator, "{\"reason\":\"x\",\"changes\":[{\"scope\":{\"level\":\"GLOBAL\"},\"value\":\"A\"}]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void submittedValuesNeverAppearInResponsesOrLogs(CapturedOutput output) throws Exception {
        String acme = unique("acme");
        String operator = token("op", Role.OPERATOR, acme);
        String secret = "SECRET-VALUE-9876543210";
        String response = publish(operator, setOne("booking.cutoff.minutes", "CLIENT", acme, null, secret, null))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(secret), "the error response echoed the submitted value");
        assertFalse(output.getAll().contains(secret), "the submitted value reached the logs");
    }

    @Test
    void unexpectedFailuresShowNoInternals() throws Exception {
        String operator = token("op", Role.OPERATOR, unique("acme"));
        String response = mvc.perform(post("/v1/change-sets").header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.TEXT_PLAIN).content("x"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("Exception"));
        assertFalse(response.contains("org.springframework"));
        assertTrue(response.contains("\"error\""));
    }

    @Test
    void anEmptyOrUnknownChangeIsRejectedWithEveryProblemListed() throws Exception {
        String operator = token("op", Role.OPERATOR, unique("acme"));
        publish(operator, "{\"reason\":\"x\",\"changes\":[]}").andExpect(status().isUnprocessableEntity());
        String unknown = "{\"reason\":\"x\",\"changes\":[{\"key\":\"no.such\",\"scope\":{\"level\":\"GLOBAL\"},\"value\":\"1\"}]}";
        publish(token("root", Role.ADMIN, null), unknown).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].message").value("unknown setting"));
    }
}
