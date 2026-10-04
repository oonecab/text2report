package com.text2report.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * Keep application-local timestamps aligned with the business timezone.
 */
@Configuration
public class TimeZoneConfig {

    public static final String BUSINESS_TIME_ZONE = "Asia/Shanghai";

    @PostConstruct
    public void setDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(BUSINESS_TIME_ZONE));
    }
}
