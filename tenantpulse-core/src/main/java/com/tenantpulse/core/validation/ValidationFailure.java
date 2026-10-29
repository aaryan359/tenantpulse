package com.tenantpulse.core.validation;

/**
 * One reason a change set was rejected. {@code key} and {@code scope} say where (either may be
 * null for problems about the whole change set). The message never contains the submitted value.
 */
public record ValidationFailure(String key, String scope, String message) {
}
