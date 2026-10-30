package com.tenantpulse.client;

import java.time.Duration;

/** A call to the config server failed. {@link #kind()} says how the client should react. */
public class ServerException extends RuntimeException {

    public enum Kind {
        /** Network error, timeout or server error: retry with backoff. */
        UNAVAILABLE,
        /** The server asked the client to slow down; honour {@link #retryAfter()} plus jitter. */
        BUSY,
        /** The server refused the request (bad or expired token, forbidden): retrying the same call will not help. */
        REJECTED
    }

    private final Kind kind;
    private final Duration retryAfter;

    public ServerException(Kind kind, String message, Duration retryAfter) {
        super(message);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public ServerException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind kind() {
        return kind;
    }

    /** The wait the server asked for, or null. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
