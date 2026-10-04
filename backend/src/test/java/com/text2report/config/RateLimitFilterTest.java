package com.text2report.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.context.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private RateLimitProperties rateLimitProperties;
    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void setUp() {
        rateLimitProperties = new RateLimitProperties();
        rateLimitFilter = new RateLimitFilter(stringRedisTemplate, rateLimitProperties, new ObjectMapper());
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("请求次数未超限_正常放行")
    void 请求次数未超限_正常放行() throws Exception {
        // given
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-1");
        traceInfo.setUserId(7);
        traceInfo.setRole("USER");
        TraceContext.set(traceInfo);
        when(zSetOperations.zCard(anyString())).thenReturn(5L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query/history");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // when
        rateLimitFilter.doFilter(request, response, chain);

        // then
        assertNotNull(chain.getRequest());
        verify(zSetOperations).add(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("普通用户超出限额_返回429")
    void 普通用户超出限额_返回429() throws Exception {
        // given
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-2");
        traceInfo.setUserId(7);
        traceInfo.setRole("USER");
        TraceContext.set(traceInfo);
        when(zSetOperations.zCard(anyString())).thenReturn(10L);
        when(zSetOperations.rangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of(new DefaultTypedTuple<>("member", (double) (System.currentTimeMillis() - 1_000L))));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        rateLimitFilter.doFilter(request, response, new MockFilterChain());

        // then
        assertEquals(429, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
    }

    @Test
    @DisplayName("不同用户限流键隔离_统计互不影响")
    void 不同用户限流键隔离_统计互不影响() throws Exception {
        // given
        when(zSetOperations.zCard(anyString())).thenReturn(0L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query/history");
        MockHttpServletResponse response = new MockHttpServletResponse();

        TraceContext.TraceInfo first = new TraceContext.TraceInfo("req-a");
        first.setUserId(1);
        first.setRole("USER");
        TraceContext.set(first);

        // when
        rateLimitFilter.doFilter(request, response, new MockFilterChain());

        TraceContext.TraceInfo second = new TraceContext.TraceInfo("req-b");
        second.setUserId(2);
        second.setRole("USER");
        TraceContext.set(second);
        rateLimitFilter.doFilter(request, response, new MockFilterChain());

        // then
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(zSetOperations, org.mockito.Mockito.times(2)).zCard(keyCaptor.capture());
        assertEquals("t2r:ratelimit:1:GET:/api/v1/query/history", keyCaptor.getAllValues().get(0));
        assertEquals("t2r:ratelimit:2:GET:/api/v1/query/history", keyCaptor.getAllValues().get(1));
    }

    @Test
    @DisplayName("管理员限额高于普通用户_不被普通用户限流逻辑影响")
    void 管理员限额高于普通用户_不被普通用户限流逻辑影响() throws Exception {
        // given
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-admin");
        traceInfo.setUserId(1);
        traceInfo.setRole("ADMIN");
        TraceContext.set(traceInfo);
        when(zSetOperations.zCard(anyString())).thenReturn(59L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query/history");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // when
        rateLimitFilter.doFilter(request, response, chain);

        // then
        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("Redis不可用时_降级放行")
    void Redis不可用时_降级放行() throws Exception {
        // given
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-redis");
        traceInfo.setUserId(7);
        traceInfo.setRole("USER");
        TraceContext.set(traceInfo);
        when(stringRedisTemplate.opsForZSet()).thenThrow(new RuntimeException("redis down"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query/history");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // when
        rateLimitFilter.doFilter(request, response, chain);

        // then
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("健康检查接口_跳过限流")
    void 健康检查接口_跳过限流() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // when
        rateLimitFilter.doFilter(request, response, chain);

        // then
        assertNotNull(chain.getRequest());
        org.mockito.Mockito.verifyNoInteractions(zSetOperations);
    }
}
