package com.tenantpulse.server.service;

import com.tenantpulse.core.validation.ValidationFailure;

import java.util.List;

/** The change set failed validation, so nothing was published and no version was used. */
public class ChangeSetRejectedException extends RuntimeException {

    private final List<ValidationFailure> failures;

    public ChangeSetRejectedException(List<ValidationFailure> failures) {
        super("change set failed validation");
        this.failures = List.copyOf(failures);
    }

    public List<ValidationFailure> failures() {
        return failures;
    }
}
