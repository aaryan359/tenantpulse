package com.tenantpulse.server.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Lets controllers declare a {@link Caller} parameter; it is built from the verified token's claims. */
@Component
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Caller.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("no authenticated caller");
        }
        return from(token.getToken());
    }

    static Caller from(Jwt jwt) {
        Role role;
        try {
            role = Role.valueOf(String.valueOf(jwt.getClaimAsString("role")));
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("token has no valid role");
        }
        String tenant = jwt.getClaimAsString("tenant");
        if (role != Role.ADMIN && (tenant == null || tenant.isBlank())) {
            throw new AccessDeniedException("token has no tenant");
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new AccessDeniedException("token has no subject");
        }
        return new Caller(subject, role, role == Role.ADMIN ? null : tenant);
    }
}
