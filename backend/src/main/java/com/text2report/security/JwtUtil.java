package com.text2report.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT utility for token parsing and validation
 */
@Slf4j
@Component
public class JwtUtil {

    private final String secret;
    private final long expirationMs;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-seconds:86400}") long expirationSeconds) {
        this.secret = secret;
        this.expirationMs = expirationSeconds * 1000;
    }

    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalStateException(
                "[SECURITY] jwt.secret 未配置或长度不足32字符，服务拒绝启动。" +
                "请在 application-local.yml 或环境变量 JWT_SECRET 中设置强密钥。");
        }
        // Enhanced entropy validation
        long uniqueChars = secret.chars().distinct().count();
        if (uniqueChars < 8) {
            throw new IllegalStateException(
                "[SECURITY] jwt.secret 字符种类不足（需至少8种不同字符），服务拒绝启动。");
        }
        // Check for sequential/repeated patterns
        if (hasSequentialPattern(secret) || hasRepeatedPattern(secret)) {
            throw new IllegalStateException(
                "[SECURITY] jwt.secret 包含易猜模式（顺序或重复），服务拒绝启动。");
        }
    }

    private boolean hasSequentialPattern(String s) {
        String lower = s.toLowerCase();
        String[] seqs = {"0123456789", "abcdefghijklmnopqrstuvwxyz", "zyxwvutsrqponmlkjihgfedcba"};
        for (String seq : seqs) {
            for (int i = 0; i <= seq.length() - 4; i++) {
                String sub = seq.substring(i, i + 4);
                if (lower.contains(sub)) return true;
            }
        }
        return false;
    }

    private boolean hasRepeatedPattern(String s) {
        // Check for 4+ same consecutive characters
        for (int i = 0; i < s.length() - 3; i++) {
            if (s.charAt(i) == s.charAt(i + 1) && s.charAt(i + 1) == s.charAt(i + 2) && s.charAt(i + 2) == s.charAt(i + 3)) {
                return true;
            }
        }
        return false;
    }

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate JWT token for user
     */
    public String generateToken(Integer userId, String username, String role, Integer orgId) {
        return generateAccessToken(userId, username, role, orgId, null);
    }

    public String generateAccessToken(Integer userId, String username, String role, Integer orgId, String jti) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        var builder = Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role)
                .claim("orgId", orgId)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSecretKey());
        if (jti != null && !jti.isBlank()) {
            builder.id(jti);
        }
        return builder.compact();
    }

    /**
     * Parse and validate JWT token
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSecretKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Validate token
     */
    public boolean validateToken(String token) {
        return parseToken(token) != null;
    }

    /**
     * Extract username from token
     */
    public String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    /**
     * Extract userId from token
     */
    public Integer getUserId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) return null;
        Object userId = claims.get("userId");
        if (userId instanceof Number) {
            return ((Number) userId).intValue();
        }
        return null;
    }

    /**
     * Extract role from token
     */
    public String getRole(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.get("role", String.class) : null;
    }

    /**
     * Extract orgId from token
     */
    public Integer getOrgId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) return null;
        Object orgId = claims.get("orgId");
        if (orgId instanceof Number) {
            return ((Number) orgId).intValue();
        }
        return null;
    }

    /**
     * Get expiration time in seconds
     * Fix: P1-5 - Used by AuthService for blacklist TTL
     */
    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }

    public String getJti(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getId() : null;
    }

    public Date getExpiration(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getExpiration() : null;
    }
}
