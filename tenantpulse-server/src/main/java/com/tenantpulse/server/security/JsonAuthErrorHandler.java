package com.tenantpulse.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenantpulse.server.api.ApiError;
import com.tenantpulse.server.observability.TenantPulseMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Answers authentication and authorisation failures with the same JSON error shape as everything else. */
@Component
public class JsonAuthErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;
    private final TenantPulseMetrics metrics;

    public JsonAuthErrorHandler(ObjectMapper mapper, TenantPulseMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        metrics.error("unauthenticated");
        write(response, HttpServletResponse.SC_UNAUTHORIZED, ApiError.of("unauthenticated", "authentication required"));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        metrics.error("forbidden");
        write(response, HttpServletResponse.SC_FORBIDDEN, ApiError.of("forbidden", "not allowed"));
    }

    private void write(HttpServletResponse response, int status, ApiError body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), body);
    }
}
