package com.tenantpulse.client;

/** The client could not get any configuration at start-up: the server is unreachable and there is no usable disk copy. */
public class ConfigUnavailableException extends RuntimeException {

    public ConfigUnavailableException(String message) {
        super(message);
    }
}
