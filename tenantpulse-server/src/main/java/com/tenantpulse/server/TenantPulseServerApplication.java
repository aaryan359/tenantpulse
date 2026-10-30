package com.tenantpulse.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TenantPulseServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantPulseServerApplication.class, args);
    }
}
