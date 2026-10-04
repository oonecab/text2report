package com.text2report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SQL Guard configuration properties
 *
 * Fix: P0-2 - Added configuration properties for sql-guard
 */
@Data
@Component
@ConfigurationProperties(prefix = "sql-guard")
public class SqlGuardProperties {

    /**
     * Whether to block SELECT * queries
     */
    private boolean blockSelectStar = true;

    /**
     * Default limit for queries without TOP/LIMIT
     */
    private int defaultLimit = 1000;

    /**
     * Query timeout in seconds
     */
    private int queryTimeoutSeconds = 30;
}
