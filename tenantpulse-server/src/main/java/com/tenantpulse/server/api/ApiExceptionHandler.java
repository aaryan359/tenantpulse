package com.tenantpulse.server.api;

import com.tenantpulse.core.exception.UnknownSettingException;
import com.tenantpulse.server.observability.TenantPulseMetrics;
import com.tenantpulse.server.service.ChangeSetRejectedException;
import com.tenantpulse.server.service.InvalidRequestException;
import com.tenantpulse.server.service.NotFoundException;
import com.tenantpulse.server.service.TooManyConnectionsException;
import com.tenantpulse.server.service.TooManyResyncsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into one clean JSON error shape. Messages describe the rule that was broken, never the
 * submitted values, and unexpected failures are logged here and shown to the caller only as "something went wrong".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final TenantPulseMetrics metrics;

    public ApiExceptionHandler(TenantPulseMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(ChangeSetRejectedException.class)
    public ResponseEntity<ApiError> rejected(ChangeSetRejectedException e) {
        metrics.error("rejected");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("change_set_rejected", e.getMessage(), e.failures()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AccessDeniedException e) {
        metrics.error("forbidden");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("forbidden", e.getMessage()));
    }

    @ExceptionHandler({NotFoundException.class, UnknownSettingException.class})
    public ResponseEntity<ApiError> notFound(RuntimeException e) {
        metrics.error("not_found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", e.getMessage()));
    }

    @ExceptionHandler(TooManyResyncsException.class)
    public ResponseEntity<ApiError> busy(TooManyResyncsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(e.retryAfterSeconds()))
                .body(ApiError.of("busy", e.getMessage()));
    }

    @ExceptionHandler(TooManyConnectionsException.class)
    public ResponseEntity<ApiError> full(TooManyConnectionsException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(ApiError.of("unavailable", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        metrics.error("bad_request");
        return ResponseEntity.badRequest().body(ApiError.of("bad_request", "request body is missing or malformed"));
    }

    /** Our own request errors carry a message written for callers, so it is returned as is. */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> invalidRequest(InvalidRequestException e) {
        metrics.error("bad_request");
        return ResponseEntity.badRequest().body(ApiError.of("bad_request", e.getMessage()));
    }

    /** Any other IllegalArgumentException may come from a library, so its message is never shown. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badArgument(IllegalArgumentException e) {
        metrics.error("bad_request");
        log.warn("rejected request with illegal argument: {}", e.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(ApiError.of("bad_request", "invalid request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse springError) {
            // Spring's own errors (404, 405, 415...) already carry the right status; keep it.
            metrics.error("http_" + springError.getStatusCode().value());
            return ResponseEntity.status(springError.getStatusCode())
                    .body(ApiError.of("http_error", "request could not be processed"));
        }
        metrics.error("internal");
        log.error("unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "something went wrong"));
    }
}
