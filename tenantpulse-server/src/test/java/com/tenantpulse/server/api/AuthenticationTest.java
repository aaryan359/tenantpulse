package com.tenantpulse.server.api;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tenantpulse.server.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Authentication and role rules: who may call what. */
class AuthenticationTest extends ApiTestSupport {

    private static final String DEV_SECRET = "dev-only-secret-change-me-dev-only-secret-change-me";

    private String signed(String secret, Instant expires, String role, String tenant, String subject) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuedAt(expires.minusSeconds(600)).expiresAt(expires);
        if (subject != null) {
            claims.subject(subject);
        }
        if (role != null) {
            claims.claim("role", role);
        }
        if (tenant != null) {
            claims.claim("tenant", tenant);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    @Test
    void noTokenIsRejected() throws Exception {
        mvc.perform(get("/v1/version")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        read("not.a.token", "/v1/version").andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() throws Exception {
        String forged = signed("another-secret-another-secret-another-secret", Instant.now().plusSeconds(600),
                "ADMIN", null, "mallory");
        read(forged, "/v1/snapshot?tenant=acme").andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String expired = signed(DEV_SECRET, Instant.now().minusSeconds(120), "SERVICE", "acme", "svc");
        read(expired, "/v1/version").andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenThatExpiredAFewSecondsAgoIsStillWithinTheSmallClockTolerance() throws Exception {
        String justExpired = signed(DEV_SECRET, Instant.now().minusSeconds(3), "SERVICE", "acme", "svc");
        read(justExpired, "/v1/version").andExpect(status().isOk());
    }

    @Test
    void aTokenWithoutASubjectIsRejected() throws Exception {
        read(signed(DEV_SECRET, Instant.now().plusSeconds(600), "SERVICE", "acme", null), "/v1/version")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutARoleIsRefused() throws Exception {
        read(signed(DEV_SECRET, Instant.now().plusSeconds(600), null, "acme", "svc"), "/v1/version")
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenWithAnUnknownRoleIsRefused() throws Exception {
        read(signed(DEV_SECRET, Instant.now().plusSeconds(600), "SUPERUSER", "acme", "svc"), "/v1/version")
                .andExpect(status().isForbidden());
    }

    @Test
    void aNonAdminTokenWithoutATenantIsRefused() throws Exception {
        read(signed(DEV_SECRET, Instant.now().plusSeconds(600), "OPERATOR", null, "op"), "/v1/version")
                .andExpect(status().isForbidden());
    }

    @Test
    void readOnlyRolesCannotChangeAnything() throws Exception {
        String acme = unique("acme");
        String body = setOne("escort.rule", "CLIENT", acme, null, "NONE", null);
        publish(token("svc", Role.SERVICE, acme), body).andExpect(status().isForbidden());
        publish(token("emp", Role.EMPLOYEE, acme), body).andExpect(status().isForbidden());
        publish(token("op", Role.OPERATOR, acme), body).andExpect(status().isOk());
    }

    @Test
    void everyRoleCanRead() throws Exception {
        String acme = unique("acme");
        for (Role role : Role.values()) {
            String tenant = role == Role.ADMIN ? null : acme;
            String path = role == Role.ADMIN ? "/v1/version?tenant=" + acme : "/v1/version";
            read(token("u-" + role, role, tenant), path).andExpect(status().isOk());
        }
    }

    @Test
    void healthIsOpenButEverythingElseIsNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
        mvc.perform(get("/some/other/path")).andExpect(status().isUnauthorized());
    }
}
