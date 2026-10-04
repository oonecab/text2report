package com.text2report.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Business database (read-only) datasource configuration
 * Points to text2report database
 */
@Configuration
public class BusinessDataSourceConfig {

    @Primary
    @Bean(name = "businessDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.business")
    public DataSource businessDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setConnectionInitSql("SET TIME ZONE '" + TimeZoneConfig.BUSINESS_TIME_ZONE + "'");
        return dataSource;
    }
}
