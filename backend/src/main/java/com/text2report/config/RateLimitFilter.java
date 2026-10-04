package com.text2report.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.ApiResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed sliding window rate limiter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMIT_PREFIX = "t2r:ratelimit:";
    private static final long WINDOW_MILLIS = 60_000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final RateLimitProperties rateLimitProperties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || "/api/v1/health".equals(uri)
                || "/api/v1/health/details".equals(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!rateLimitProperties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            RateLimitDecision decision = buildDecision(request);
            long now = System.currentTimeMillis();
            long windowStart = now - WINDOW_MILLIS;

            ZSetOperations<String, String> zSetOperations = stringRedisTemplate.opsForZSet();
            zSetOperations.removeRangeByScore(decision.key(), 0, windowStart);
            Long currentCount = zSetOperations.zCard(decision.key());
            long count = currentCount != null ? currentCount : 0L;

            if (count >= decision.limit()) {
                long retryAfterSeconds = calculateRetryAfterSeconds(zSetOperations, decision.key(), now);
                response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
                writeRateLimitedResponse(response);
                return;
            }

            zSetOperations.add(decision.key(), UUID.randomUUID().toString(), now);
            stringRedisTemplate.expire(decision.key(), Duration.ofSeconds(60));
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("Rate limit degraded because Redis is unavailable: {}", e.getMessage());
            filterChain.doFilter(request, response);
        }
    }

    private RateLimitDecision buildDecision(HttpServletRequest request) {
        String endpoint = request.getMethod() + ":" + request.getRequestURI();
        Integer userId = TraceContext.getUserId();
        String role = TraceContext.getRole();

        if (userId == null) {
            String identifier = resolveClientIp(request);
            return new RateLimitDecision(
                    RATE_LIMIT_PREFIX + identifier + ":" + endpoint,
                    rateLimitProperties.getAnonymousPerMinute()
            );
        }

        int limit;
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/v1/query".equals(request.getRequestURI())) {
            limit = rateLimitProperties.getQueryPerMinute();
        } else if ("ADMIN".equalsIgnoreCase(role)) {
            limit = rateLimitProperties.getAdminPerMinute();
        } else {
            limit = rateLimitProperties.getUserPerMinute();
        }
        return new RateLimitDecision(
                RATE_LIMIT_PREFIX + userId + ":" + endpoint,
                limit
        );
    }

    private long calculateRetryAfterSeconds(ZSetOperations<String, String> zSetOperations, String key, long now) {
        Set<ZSetOperations.TypedTuple<String>> tuples = zSetOperations.rangeWithScores(key, 0, 0);
        if (tuples == null || tuples.isEmpty()) {
            return 60L;
        }
        Double oldestScore = tuples.iterator().next().getScore();
        if (oldestScore == null) {
            return 60L;
        }
        long remainingMillis = Math.max(1L, WINDOW_MILLIS - (now - oldestScore.longValue()));
        return (remainingMillis + 999L) / 1000L;
    }

    private void writeRateLimitedResponse(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        ApiResult<Void> result = ApiResult.<Void>error(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试")
                .withRequestId(TraceContext.getRequestId());
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private record RateLimitDecision(String key, int limit) {
    }
}
