package com.tenantpulse.server.service;

/** The request itself is wrong (bad scope, missing field). The message is written by us and safe to return as is. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
