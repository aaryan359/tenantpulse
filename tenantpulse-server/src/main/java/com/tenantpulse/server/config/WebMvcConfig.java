package com.tenantpulse.server.config;

import com.tenantpulse.server.security.CallerArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CallerArgumentResolver callerResolver;

    public WebMvcConfig(CallerArgumentResolver callerResolver) {
        this.callerResolver = callerResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(callerResolver);
    }
}
