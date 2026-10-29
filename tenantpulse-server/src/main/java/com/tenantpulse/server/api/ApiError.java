package com.tenantpulse.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tenantpulse.core.validation.ValidationFailure;

import java.util.List;

/** The one error shape every failed request returns. Never contains stack traces or submitted values. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, List<ValidationFailure> details) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, null);
    }
}
