package com.text2report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.model.dto.QueryResultDTO;
import com.text2report.model.dto.ResultProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryCacheServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RedisConnectionFactory redisConnectionFactory;
    @Mock
    private RedisConnection redisConnection;
    @Mock
    private Cursor<byte[]> cursor;

    private QueryCacheService queryCacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        queryCacheService = new QueryCacheService(stringRedisTemplate, objectMapper);
        ReflectionTestUtils.setField(queryCacheService, "queryResultTtlSeconds", 1800L);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Should return cached result on hit")
    void shouldReturnCachedResultOnHit() throws Exception {
        QueryResultDTO queryResult = sampleResult();
        when(valueOperations.get(any())).thenReturn(objectMapper.writeValueAsString(queryResult));

        Optional<QueryResultDTO> cached = queryCacheService.getCachedResult("毛利", 2);

        assertTrue(cached.isPresent());
        assertEquals("summary", cached.get().getSummary());
    }

    @Test
    @DisplayName("Should return empty on cache miss")
    void shouldReturnEmptyOnCacheMiss() {
        when(valueOperations.get(any())).thenReturn(null);

        Optional<QueryResultDTO> cached = queryCacheService.getCachedResult("毛利", 2);

        assertTrue(cached.isEmpty());
    }

    @Test
    @DisplayName("Should write cache with configured ttl")
    void shouldWriteCacheWithConfiguredTtl() {
        QueryResultDTO queryResult = sampleResult();

        queryCacheService.cacheResult("毛利", 2, queryResult);

        verify(valueOperations).set(any(), any(), eq(Duration.ofSeconds(1800)));
    }

    @Test
    @DisplayName("Should degrade silently when Redis unavailable on read")
    void shouldDegradeSilentlyWhenRedisUnavailableOnRead() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        Optional<QueryResultDTO> cached = queryCacheService.getCachedResult("毛利", 2);

        assertTrue(cached.isEmpty());
    }

    @Test
    @DisplayName("Should delete exact cache key on invalidate")
    void shouldDeleteExactCacheKeyOnInvalidate() {
        queryCacheService.invalidateCache("毛利", 2);

        verify(stringRedisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("Should scan and delete all query cache keys")
    void shouldScanAndDeleteAllQueryCacheKeys() throws Exception {
        when(stringRedisTemplate.getConnectionFactory()).thenReturn(redisConnectionFactory);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("t2r:cache:query:v2:abc".getBytes());

        queryCacheService.invalidateAllQueryCache();

        verify(redisConnection).del("t2r:cache:query:v2:abc".getBytes());
        verify(cursor).close();
        verify(redisConnection).close();
    }

    private QueryResultDTO sampleResult() {
        return QueryResultDTO.builder()
                .requestId("req-1")
                .sql("SELECT 1")
                .table(QueryResultDTO.TableData.builder()
                        .columns(List.of(QueryResultDTO.ColumnMeta.builder().key("org_name").title("组织").dataType("string").build()))
                        .rows(List.of(Map.of("org_name", "子公司A")))
                        .build())
                .resultProfile(ResultProfile.builder().recommendedChart("bar").rowCount(1).columnCount(1).columnNames(List.of("org_name")).hasData(true).build())
                .summary("summary")
                .warnings(List.of())
                .audit(QueryResultDTO.AuditInfo.builder().retryCount(0).executionMs(10L).build())
                .build();
    }
}
