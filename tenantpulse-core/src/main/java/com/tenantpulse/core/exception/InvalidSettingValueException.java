package com.tenantpulse.core.exception;

/**
 * A value failed parsing or validation. The message describes the rule, never echoes the
 * offending input, so it is safe to return to callers and write to logs.
 */
public class InvalidSettingValueException extends RuntimeException {

    public InvalidSettingValueException(String message) {
        super(message);
    }
}
