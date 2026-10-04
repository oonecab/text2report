package com.text2report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rate limit configuration properties.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private int anonymousPerMinute = 10;
    private int userPerMinute = 30;
    private int adminPerMinute = 60;
    private int queryPerMinute = 10;
}
