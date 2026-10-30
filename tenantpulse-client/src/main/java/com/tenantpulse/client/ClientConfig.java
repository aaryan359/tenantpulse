package com.tenantpulse.client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Settings for one embedded client. Build with {@link #builder(URI, String, Supplier)}; everything else has a default. */
public final class ClientConfig {

    private final URI serverUrl;
    private final String tenantId;
    private final Supplier<String> tokenSupplier;
    private final Path cacheFile;
    private final Duration versionCheckInterval;
    private final Duration requestTimeout;
    private final Duration backoffInitial;
    private final Duration backoffMax;
    private final Duration startupTimeout;
    private final Duration startupJitter;
    private final Duration streamIdleTimeout;

    private ClientConfig(Builder b) {
        this.serverUrl = b.serverUrl;
        this.tenantId = b.tenantId;
        this.tokenSupplier = b.tokenSupplier;
        this.cacheFile = b.cacheFile;
        this.versionCheckInterval = b.versionCheckInterval;
        this.requestTimeout = b.requestTimeout;
        this.backoffInitial = b.backoffInitial;
        this.backoffMax = b.backoffMax;
        this.startupTimeout = b.startupTimeout;
        this.startupJitter = b.startupJitter;
        this.streamIdleTimeout = b.streamIdleTimeout;
    }

    /**
     * @param serverUrl     base URL of the config server
     * @param tenantId      the client (tenant) this service reads for; every read resolves inside it
     * @param tokenSupplier called for each request, so a refreshed token is picked up without a restart
     */
    public static Builder builder(URI serverUrl, String tenantId, Supplier<String> tokenSupplier) {
        return new Builder(serverUrl, tenantId, tokenSupplier);
    }

    public URI serverUrl() {
        return serverUrl;
    }

    public String tenantId() {
        return tenantId;
    }

    public Supplier<String> tokenSupplier() {
        return tokenSupplier;
    }

    /** Where the last good snapshot is kept for start-up while the server is down; null disables the disk copy. */
    public Path cacheFile() {
        return cacheFile;
    }

    /** How often the client checks the server's version. This is what bounds staleness if a push is missed. */
    public Duration versionCheckInterval() {
        return versionCheckInterval;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration backoffInitial() {
        return backoffInitial;
    }

    public Duration backoffMax() {
        return backoffMax;
    }

    /** How long {@link TenantPulseClient#start()} waits for a first snapshot when there is no disk copy. */
    public Duration startupTimeout() {
        return startupTimeout;
    }

    /** Random delay before the first network call when a disk copy lets the service start right away. */
    public Duration startupJitter() {
        return startupJitter;
    }

    /** A live connection with no traffic (not even a keepalive) for this long is treated as dead. */
    public Duration streamIdleTimeout() {
        return streamIdleTimeout;
    }

    public static final class Builder {
        private final URI serverUrl;
        private final String tenantId;
        private final Supplier<String> tokenSupplier;
        private Path cacheFile;
        private Duration versionCheckInterval = Duration.ofSeconds(2);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private Duration backoffInitial = Duration.ofMillis(500);
        private Duration backoffMax = Duration.ofSeconds(30);
        private Duration startupTimeout = Duration.ofSeconds(10);
        private Duration startupJitter = Duration.ofMillis(500);
        private Duration streamIdleTimeout = Duration.ofSeconds(45);

        private Builder(URI serverUrl, String tenantId, Supplier<String> tokenSupplier) {
            this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId is required");
            }
            this.tenantId = tenantId;
            this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        }

        public Builder cacheFile(Path cacheFile) {
            this.cacheFile = cacheFile;
            return this;
        }

        public Builder versionCheckInterval(Duration value) {
            this.versionCheckInterval = positive(value, "versionCheckInterval");
            return this;
        }

        public Builder requestTimeout(Duration value) {
            this.requestTimeout = positive(value, "requestTimeout");
            return this;
        }

        public Builder backoff(Duration initial, Duration max) {
            this.backoffInitial = positive(initial, "backoff initial");
            this.backoffMax = positive(max, "backoff max");
            return this;
        }

        public Builder startupTimeout(Duration value) {
            this.startupTimeout = positive(value, "startupTimeout");
            return this;
        }

        public Builder startupJitter(Duration value) {
            this.startupJitter = Objects.requireNonNull(value, "startupJitter");
            return this;
        }

        public Builder streamIdleTimeout(Duration value) {
            this.streamIdleTimeout = positive(value, "streamIdleTimeout");
            return this;
        }

        public ClientConfig build() {
            return new ClientConfig(this);
        }

        private static Duration positive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
