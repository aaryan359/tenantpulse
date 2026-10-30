package com.tenantpulse.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Tunables for the server, bound from {@code tenantpulse.*}. Every value has a safe default. */
@ConfigurationProperties(prefix = "tenantpulse")
public record TenantPulseProperties(
        @DefaultValue Sse sse,
        @DefaultValue Changes changes,
        @DefaultValue Resync resync,
        @DefaultValue Security security,
        @DefaultValue("1s") Duration refreshInterval) {

    /** Live push connections. */
    public record Sse(@DefaultValue("5000") int maxConnections,
                      @DefaultValue("15s") Duration heartbeat,
                      @DefaultValue("30m") Duration connectionLifetime) {
    }

    /** Limits on the "what changed since version N" answer. */
    public record Changes(@DefaultValue("500") int maxChangeSets) {
    }

    /** Protects the server from a reconnect storm: how many snapshot/delta requests run at once. */
    public record Resync(@DefaultValue("64") int maxConcurrent,
                         @DefaultValue("2") int retryAfterSeconds) {
    }

    /** Token settings. The default secret is for local development only and triggers a warning at start-up. */
    public record Security(
            @DefaultValue("dev-only-secret-change-me-dev-only-secret-change-me") String jwtSecret,
            @DefaultValue("false") boolean devTokenEndpointEnabled,
            @DefaultValue("1h") Duration devTokenTtl) {
    }
}
