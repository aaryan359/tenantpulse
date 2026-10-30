package com.tenantpulse.client;

import com.tenantpulse.core.wire.Wire;

import java.util.function.LongConsumer;

/**
 * What the client needs from the server. The real implementation talks HTTP; tests plug in a fake to inject
 * failures (server down, duplicate or out-of-order messages) without a network.
 */
public interface ConfigServerApi {

    /** The full snapshot for this client's tenant. */
    Wire.SnapshotResponse snapshot() throws ServerException;

    /** What changed after {@code since}. */
    Wire.ChangesResponse changes(long since) throws ServerException;

    /** The cheap version check; {@code clientVersion} lets the server track how far behind clients are. */
    Wire.VersionResponse version(long clientVersion) throws ServerException;

    /**
     * Opens the live-push connection and calls {@code onVersion} for every version announced. Blocks until the
     * connection ends, then returns normally; throws if it could not be opened or broke.
     */
    void stream(LongConsumer onVersion) throws ServerException;

    /** Releases connections; further calls fail. */
    default void close() {
    }
}
