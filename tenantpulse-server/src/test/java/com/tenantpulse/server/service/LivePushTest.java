package com.tenantpulse.server.service;

import com.tenantpulse.server.api.ApiTestSupport;
import com.tenantpulse.server.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Live push: connected clients hear about changes that concern their tenant, and only those. */
class LivePushTest extends ApiTestSupport {

    @Autowired
    ChangeBroadcaster broadcaster;

    private MvcResult openStream(String token) throws Exception {
        return mvc.perform(get("/v1/stream").header("Authorization", "Bearer " + token))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private static String events(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString();
    }

    private static void eventually(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    void aChangeIsPushedToItsTenantAndNotToAnother() throws Exception {
        String acme = unique("acme");
        String globex = unique("globex");
        MvcResult acmeStream = openStream(token("svc-a", Role.SERVICE, acme));
        MvcResult globexStream = openStream(token("svc-g", Role.SERVICE, globex));

        long version = body(publish(token("op-a", Role.OPERATOR, acme),
                setOne("escort.rule", "CLIENT", acme, null, "NONE", null))).get("version").asLong();

        eventually("acme to be told", () -> {
            try {
                return events(acmeStream).contains("data:" + version);
            } catch (Exception e) {
                return false;
            }
        });
        Thread.sleep(300);
        assertFalse(events(globexStream).contains("data:" + version), "globex was told about acme's change");
    }

    @Test
    void aPlatformWideChangeIsPushedToEveryone() throws Exception {
        MvcResult one = openStream(token("svc-1", Role.SERVICE, unique("a")));
        MvcResult two = openStream(token("svc-2", Role.SERVICE, unique("b")));

        long version = body(publish(token("root", Role.ADMIN, null),
                setOne("escort.rule", "GLOBAL", null, null, "REQUIRED", null))).get("version").asLong();

        eventually("both streams to be told", () -> {
            try {
                return events(one).contains("data:" + version) && events(two).contains("data:" + version);
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    void aNewConnectionIsToldTheCurrentVersionAtOnce() throws Exception {
        String acme = unique("acme");
        long version = body(publish(token("op", Role.OPERATOR, acme),
                setOne("escort.rule", "CLIENT", acme, null, "NONE", null))).get("version").asLong();

        MvcResult stream = openStream(token("svc", Role.SERVICE, acme));
        eventually("the initial version", () -> {
            try {
                return events(stream).contains("data:" + version);
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    void streamsRequireAToken() throws Exception {
        mvc.perform(get("/v1/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void theNotificationQueueNeverGrowsBeyondOnePerConnection() throws Exception {
        MvcResult stream = openStream(token("svc", Role.SERVICE, unique("acme")));
        int connections = broadcaster.connectionCount();
        for (long v = 1; v <= 10_000; v++) {
            broadcaster.notifyChange(1_000_000 + v, null);
        }
        assertTrue(broadcaster.queueDepth() <= connections,
                "at most one waiting notification per connection, got " + broadcaster.queueDepth());
        eventually("the newest version to arrive", () -> {
            try {
                return events(stream).contains("data:1010000");
            } catch (Exception e) {
                return false;
            }
        });
    }
}
