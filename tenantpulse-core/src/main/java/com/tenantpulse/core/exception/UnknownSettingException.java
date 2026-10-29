package com.tenantpulse.core.exception;

/** A key was read or changed that has no definition. */
public class UnknownSettingException extends RuntimeException {

    public UnknownSettingException(String key) {
        super("no setting defined with key '" + key + "'");
    }
}
