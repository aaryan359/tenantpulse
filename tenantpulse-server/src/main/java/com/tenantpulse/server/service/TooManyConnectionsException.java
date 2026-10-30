package com.tenantpulse.server.service;

/** The server is at its limit of live push connections; the client should retry later. */
public class TooManyConnectionsException extends RuntimeException {

    public TooManyConnectionsException() {
        super("too many live connections");
    }
}
