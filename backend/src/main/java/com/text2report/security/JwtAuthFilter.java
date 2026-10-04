package com.text2report.security;

import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.ApiResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT authentication filter - extracts and validates JWT from Authorization header,
 * sets SecurityContext and TraceContext
 *
 * Fix: P0-3 - Added Redis blacklist check
 * Fix: P0-4 - No longer overwrites TraceContext requestId
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String SESSION_KEY_PREFIX = "t2r:session:";
    private static final String SESSION_VALUE_SEPARATOR = "|";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final JwtTokenResolver jwtTokenResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            JwtTokenResolver.ResolvedToken resolvedToken = jwtTokenResolver.resolve(request);
            if (resolvedToken.malformed()) {
                // Malformed token (e.g., "Bearer " with no actual token, or "Basic xxx")
                sendUnauthorized(response, "Authorization 头格式错误");
                return;
            }

            String token = resolvedToken.token();
            if (StringUtils.hasText(token)) {
                // Hard blocks: blacklisted, expired, or otherwise invalid tokens
                if (isTokenBlacklisted(token)) {
                    log.debug("Token is blacklisted");
                    sendUnauthorized(response, "token 已失效，请重新登录");
                    return;
                }
                if (!jwtUtil.validateToken(token)) {
                    log.debug("Invalid JWT token");
                    sendUnauthorized(response, "token 无效或已过期");
                    return;
                }
                // Attempt authentication; JTI mismatch = skip auth but let request through
                authenticateIfSessionValid(token);
            }

            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * Authenticate the user only if the session JTI matches.
     * When the JTI does not match (e.g. stale cookie after Redis restart),
     * skip authentication silently so Spring Security can handle permitAll()
     * endpoints correctly.
     */
    private void authenticateIfSessionValid(String token) {
        Claims claims = jwtUtil.parseToken(token);
        String username = claims != null ? claims.getSubject() : null;
        Integer userId = extractUserId(claims);
        String role = claims != null ? claims.get("role", String.class) : null;
        Integer orgId = extractOrgId(claims);
        String tokenJti = claims != null ? claims.getId() : null;

        if (!isCurrentSession(userId, tokenJti)) {
            log.debug("Session jti mismatch for userId={} – not authenticating", userId);
            return;
        }

        if (username != null) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            username,
                            null,
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
                    );
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Fix: P0-4 - Only set user info, don't overwrite requestId from TraceFilter
            TraceContext.setUserId(userId);
            TraceContext.setOrgId(orgId);
            TraceContext.setRole(role);

            log.debug("Authenticated user: {}, role: {}, orgId: {}", username, role, orgId);
        }
    }

    private boolean isCurrentSession(Integer userId, String tokenJti) {
        if (userId == null || !StringUtils.hasText(tokenJti)) {
            return false;
        }
        String currentJti = extractStoredJti(stringRedisTemplate.opsForValue().get(SESSION_KEY_PREFIX + userId));
        return tokenJti.equals(currentJti);
    }

    private String extractStoredJti(String sessionValue) {
        if (!StringUtils.hasText(sessionValue)) {
            return null;
        }
        int separatorIndex = sessionValue.indexOf(SESSION_VALUE_SEPARATOR);
        if (separatorIndex < 0) {
            return sessionValue;
        }
        return sessionValue.substring(0, separatorIndex);
    }

    private Integer extractUserId(Claims claims) {
        if (claims == null) {
            return null;
        }
        Object userId = claims.get("userId");
        if (userId instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private Integer extractOrgId(Claims claims) {
        if (claims == null) {
            return null;
        }
        Object orgId = claims.get("orgId");
        if (orgId instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    /**
     * Check if token is blacklisted in Redis
     * Fix: P0-3
     */
    private boolean isTokenBlacklisted(String token) {
        String blacklistKey = JwtConstants.BLACKLIST_PREFIX + token;
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(blacklistKey));
        } catch (Exception e) {
            // Redis unavailable - fail open, only validate signature
            log.warn("Redis blacklist check failed, proceeding without blacklist check: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send standard 401 unauthorized response
     * Fix: NEW-CRITICAL - Ensure all auth failures return proper 401 JSON
     */
    private void sendUnauthorized(HttpServletResponse response, String message) {
        try {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            String requestId = TraceContext.getRequestId();
            ApiResult<Void> body = ApiResult.<Void>error(ErrorCode.UNAUTHORIZED, message)
                    .withRequestId(requestId != null ? requestId : "unknown");
            response.getWriter().write(OBJECT_MAPPER.writeValueAsString(body));
        } catch (IOException e) {
            log.error("Failed to write unauthorized response", e);
        }
    }
}
