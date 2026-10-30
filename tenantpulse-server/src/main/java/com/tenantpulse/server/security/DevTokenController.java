package com.tenantpulse.server.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tenantpulse.server.config.TenantPulseProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Hands out tokens for local development and the demo, since the real identity provider is outside this project.
 * It exists only when {@code tenantpulse.security.dev-token-endpoint-enabled=true} and is off by default, so a
 * production deployment cannot mint tokens by accident.
 */
@RestController
@ConditionalOnProperty(name = "tenantpulse.security.dev-token-endpoint-enabled", havingValue = "true")
public class DevTokenController {

    private final JwtEncoder encoder;
    private final TenantPulseProperties.Security security;

    public DevTokenController(TenantPulseProperties properties) {
        this.security = properties.security();
        byte[] key = security.jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
    }

    public record TokenRequest(String subject, Role role, String tenant) {
    }

    @PostMapping("/dev/token")
    public Map<String, String> issue(@RequestBody TokenRequest request) {
        if (request.subject() == null || request.subject().isBlank() || request.role() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject and role are required");
        }
        if (request.role() != Role.ADMIN && (request.tenant() == null || request.tenant().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenant is required for this role");
        }
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(request.subject())
                .issuedAt(now)
                .expiresAt(now.plus(security.devTokenTtl()))
                .claim("role", request.role().name());
        if (request.tenant() != null && !request.tenant().isBlank()) {
            claims.claim("tenant", request.tenant());
        }
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        return Map.of("token", token);
    }
}
