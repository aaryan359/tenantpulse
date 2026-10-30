package com.tenantpulse.server.security;

import com.tenantpulse.server.config.TenantPulseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Token-based security: every request except health and metrics must carry a valid signed token, reads are open to
 * any role, and anything that changes configuration needs OPERATOR or ADMIN. Which tenant a caller may touch is
 * decided in {@link TenantAccessPolicy}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String DEV_SECRET_PREFIX = "dev-only-secret";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(10);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonAuthErrorHandler errors) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers("/dev/token").permitAll()
                        .requestMatchers("/", "/index.html").permitAll()   // the demo console page; it holds no data
                        .requestMatchers(HttpMethod.GET, "/v1/**").hasAnyRole("SERVICE", "EMPLOYEE", "OPERATOR", "ADMIN")
                        .requestMatchers("/v1/**").hasAnyRole("OPERATOR", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(TenantPulseProperties properties) {
        String secret = properties.security().jwtSecret();
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("tenantpulse.security.jwt-secret must be at least 32 bytes");
        }
        if (secret.startsWith(DEV_SECRET_PREFIX)) {
            log.warn("using the built-in development token secret; set TENANTPULSE_JWT_SECRET before deploying");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        // Spring tolerates 60 s of clock difference by default; allow a small, explicit amount instead.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW), new RequiredClaimsValidator()));
        return decoder;
    }

    /** A token must name its subject and carry an expiry; one missing either is never valid. */
    private static final class RequiredClaimsValidator implements OAuth2TokenValidator<Jwt> {
        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            boolean complete = jwt.getSubject() != null && jwt.getExpiresAt() != null;
            return complete ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "incomplete token", null));
        }
    }
}
