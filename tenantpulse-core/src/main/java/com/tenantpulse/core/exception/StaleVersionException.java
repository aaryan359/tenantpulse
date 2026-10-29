package com.tenantpulse.core.exception;

/** An update tried to move a snapshot to a version that is not newer than the current one. */
public class StaleVersionException extends RuntimeException {

    private final long currentVersion;
    private final long offeredVersion;

    public StaleVersionException(long currentVersion, long offeredVersion) {
        super("version " + offeredVersion + " is not newer than current version " + currentVersion);
        this.currentVersion = currentVersion;
        this.offeredVersion = offeredVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }

    public long offeredVersion() {
        return offeredVersion;
    }
}
