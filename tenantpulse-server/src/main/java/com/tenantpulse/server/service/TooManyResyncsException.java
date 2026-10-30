package com.tenantpulse.server.service;

/** Too many clients are fetching snapshots or deltas at once; the client should retry after a short, jittered wait. */
public class TooManyResyncsException extends RuntimeException {

    private final int retryAfterSeconds;

    public TooManyResyncsException(int retryAfterSeconds) {
        super("server is busy, retry shortly");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
