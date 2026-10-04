package com.text2report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.model.dto.QueryResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Query result cache backed by Redis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryCacheService {

    // Bump the namespace when query-generation semantics change so old cached SQL does not leak into new flows.
    private static final String QUERY_CACHE_PREFIX = "t2r:cache:query:v2:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${cache.query-result-ttl:1800}")
    private long queryResultTtlSeconds;

    public Optional<QueryResultDTO> getCachedResult(String question, Integer orgId) {
        return getCachedResult(question, orgId, "legacy-config");
    }

    public Optional<QueryResultDTO> getCachedResult(String question, Integer orgId, String modelSignature) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(buildCacheKey(question, orgId, modelSignature));
            if (cached == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(cached, QueryResultDTO.class));
        } catch (Exception e) {
            log.warn("Failed to read query cache, degrading to miss: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void cacheResult(String question, Integer orgId, QueryResultDTO result) {
        cacheResult(question, orgId, "legacy-config", result);
    }

    public void cacheResult(String question, Integer orgId, String modelSignature, QueryResultDTO result) {
        if (result == null) {
            return;
        }

        try {
            String cacheKey = buildCacheKey(question, orgId, modelSignature);
            String payload = objectMapper.writeValueAsString(result);
            stringRedisTemplate.opsForValue().set(cacheKey, payload, Duration.ofSeconds(queryResultTtlSeconds));
        } catch (Exception e) {
            log.warn("Failed to write query cache, degrading silently: {}", e.getMessage());
        }
    }

    public void invalidateCache(String question, Integer orgId) {
        try {
            stringRedisTemplate.delete(buildCacheKey(question, orgId));
        } catch (Exception e) {
            log.warn("Failed to invalidate query cache: {}", e.getMessage());
        }
    }

    public void invalidateAllQueryCache() {
        RedisConnectionFactory connectionFactory = stringRedisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
            return;
        }

        try (RedisConnection connection = connectionFactory.getConnection();
             var cursor = connection.scan(ScanOptions.scanOptions().match(QUERY_CACHE_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                connection.del(cursor.next());
            }
        } catch (Exception e) {
            log.warn("Failed to invalidate all query cache: {}", e.getMessage());
        }
    }

    String buildCacheKey(String question, Integer orgId) {
        return buildCacheKey(question, orgId, "legacy-config");
    }

    String buildCacheKey(String question, Integer orgId, String modelSignature) {
        String normalized = (question == null ? "" : question.trim().toLowerCase()) + ":" + orgId + ":" + (modelSignature == null ? "" : modelSignature);
        return QUERY_CACHE_PREFIX + md5(normalized);
    }

    private String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate cache key", e);
        }
    }
}
