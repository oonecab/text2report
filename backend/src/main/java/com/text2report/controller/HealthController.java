package com.text2report.controller;

import com.text2report.common.result.ApiResult;
import com.text2report.model.dto.LlmAvailabilityVO;
import com.text2report.service.LlmProviderConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Health check controller
 *
 * Fix: P1-2 - Added @Qualifier for DataSource injection, restricted /details to admin
 * Fix: P4-4 - Manual constructor instead of @RequiredArgsConstructor for @Qualifier to work
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final Instant startedAt = Instant.now();
    private final DataSource businessDataSource;
    private final DataSource metaDataSource;
    private final StringRedisTemplate stringRedisTemplate;
    private final LlmProviderConfigService llmProviderConfigService;
    private final String appVersion;

    public HealthController(
            @Qualifier("businessDataSource") DataSource businessDataSource,
            @Qualifier("metaDataSource") DataSource metaDataSource,
            StringRedisTemplate stringRedisTemplate,
            LlmProviderConfigService llmProviderConfigService,
            @Value("${app.version:1.0.0}") String appVersion) {
        this.businessDataSource = businessDataSource;
        this.metaDataSource = metaDataSource;
        this.stringRedisTemplate = stringRedisTemplate;
        this.llmProviderConfigService = llmProviderConfigService;
        this.appVersion = appVersion;
    }

    /**
     * Basic health check
     */
    @GetMapping
    public ApiResult<HealthStatus> health() {
        HealthStatus status = new HealthStatus();
        status.setStatus("UP");
        status.setTimestamp(LocalDateTime.now().toString());
        return ApiResult.success(status);
    }

    /**
     * Detailed health check with dependency status (admin only)
     */
    @GetMapping("/details")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<HealthStatus> healthDetails() {
        HealthStatus status = new HealthStatus();
        status.setTimestamp(LocalDateTime.now().toString());

        Map<String, ComponentStatus> components = new HashMap<>();

        // Check business datasource
        components.put("businessDb", checkDatabase(businessDataSource, "business"));
        // Check meta datasource
        components.put("metaDb", checkDatabase(metaDataSource, "meta"));
        // Check redis
        components.put("redis", checkRedis());
        // Check llm config
        components.put("llm", checkLlm());

        status.setComponents(components);
        status.setUptime(Duration.between(startedAt, Instant.now()).toSeconds() + "s");
        status.setVersion(appVersion);

        // Overall status is UP only if all components are UP
        boolean allUp = components.values().stream()
                .allMatch(c -> "UP".equals(c.getStatus()));
        status.setStatus(allUp ? "UP" : "DEGRADED");

        return ApiResult.success(status);
    }

    private ComponentStatus checkDatabase(DataSource ds, String name) {
        ComponentStatus component = new ComponentStatus();
        component.setName(name);

        try (Connection conn = ds.getConnection();
             PreparedStatement statement = conn.prepareStatement("SELECT 1")) {
            if (statement.execute()) {
                component.setStatus("UP");
                component.setMessage("SELECT 1 successful");
            } else {
                component.setStatus("DOWN");
                component.setMessage("SELECT 1 failed");
            }
        } catch (Exception e) {
            // Fix: P1-2 - Don't expose exception details to frontend
            log.error("Database health check failed for {}", name, e);
            component.setStatus("DOWN");
            component.setMessage("Connection failed");
        }

        return component;
    }

    private ComponentStatus checkRedis() {
        ComponentStatus component = new ComponentStatus();
        component.setName("redis");

        try (var connection = stringRedisTemplate.getConnectionFactory().getConnection()) {
            String pong = connection.ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                component.setStatus("UP");
                component.setMessage("Ping successful");
            } else {
                component.setStatus("DOWN");
                component.setMessage("Ping failed");
            }
        } catch (Exception e) {
            // Fix: P1-2 - Don't expose exception details to frontend
            log.error("Redis health check failed", e);
            component.setStatus("DOWN");
            component.setMessage("Connection failed");
        }

        return component;
    }

    private ComponentStatus checkLlm() {
        ComponentStatus component = new ComponentStatus();
        component.setName("llm");

        try {
            LlmAvailabilityVO availability = llmProviderConfigService.getAvailability();
            boolean configured = Boolean.TRUE.equals(availability.getConfigured());

            component.setStatus(configured ? "UP" : "DOWN");
            component.setMessage(configured
                    ? "Routing configured for provider " + availability.getProviderKey()
                    : availability.getMessage());
        } catch (Exception e) {
            log.error("LLM health check failed", e);
            component.setStatus("DOWN");
            component.setMessage("Model routing check failed");
        }
        return component;
    }

    @lombok.Data
    public static class HealthStatus {
        private String status;
        private String timestamp;
        private Map<String, ComponentStatus> components;
        private String uptime;
        private String version;
    }

    @lombok.Data
    public static class ComponentStatus {
        private String name;
        private String status;
        private String message;
    }
}
