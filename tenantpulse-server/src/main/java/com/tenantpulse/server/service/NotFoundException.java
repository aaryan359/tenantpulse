package com.tenantpulse.server.service;

/** The thing asked for (a version, a rollout) does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
