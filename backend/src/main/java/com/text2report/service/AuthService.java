package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.SysUser;
import com.text2report.domain.meta.entity.SysUserOrg;
import com.text2report.domain.meta.mapper.SysUserMapper;
import com.text2report.domain.meta.mapper.SysUserOrgMapper;
import com.text2report.model.dto.UserDTO;
import com.text2report.security.JwtConstants;
import com.text2report.security.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Authentication service backed by sys_user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String SESSION_KEY_PREFIX = "t2r:session:";
    private static final String SESSION_VALUE_SEPARATOR = "|";

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;
    private final SysUserMapper sysUserMapper;
    private final SysUserOrgMapper sysUserOrgMapper;
    private final UserService userService;

    public LoginResponse login(String username, String password) {
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        sysUserMapper.updateLastLoginAt(user.getId(), LocalDateTime.now());
        SysUserOrg primary = sysUserOrgMapper.selectPrimaryOrg(user.getId());
        Integer orgId = primary != null ? primary.getOrgId() : null;
        String jti = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(
                buildSessionKey(user.getId()),
                buildSessionValue(jti),
                jwtUtil.getExpirationSeconds(),
                TimeUnit.SECONDS
        );
        String token = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), user.getRole().toUpperCase(), orgId, jti);

        LoginResponse response = new LoginResponse();
        response.setAccessToken(token);
        response.setExpiresIn(jwtUtil.getExpirationSeconds());
        response.setUser(userService.getUserById(user.getId()));
        return response;
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            return;
        }
        Integer userId = extractUserId(claims);
        String tokenJti = extractJti(claims);
        if (userId != null && tokenJti != null && tokenJti.equals(getCurrentJti(userId))) {
            stringRedisTemplate.delete(buildSessionKey(userId));
        }
        blacklistToken(token, claims.getExpiration());
    }

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (!jwtUtil.validateToken(token)) {
            return false;
        }
        if (isTokenBlacklisted(token)) {
            return false;
        }
        Claims claims = jwtUtil.parseToken(token);
        Integer userId = extractUserId(claims);
        String tokenJti = extractJti(claims);
        if (userId == null || tokenJti == null || tokenJti.isBlank()) {
            return false;
        }
        return Objects.equals(getCurrentJti(userId), tokenJti);
    }

    public UserDTO getCurrentUser(String username) {
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return userService.getUserById(user.getId());
    }

    @Data
    public static class LoginResponse {
        private long expiresIn;
        private UserDTO user;
        private String accessToken;
    }

    private String buildSessionKey(Integer userId) {
        return SESSION_KEY_PREFIX + userId;
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

    private String extractJti(Claims claims) {
        return claims != null ? claims.getId() : null;
    }

    private String getCurrentJti(Integer userId) {
        return extractStoredJti(stringRedisTemplate.opsForValue().get(buildSessionKey(userId)));
    }

    private String buildSessionValue(String jti) {
        return jti + SESSION_VALUE_SEPARATOR + System.currentTimeMillis();
    }

    private String extractStoredJti(String sessionValue) {
        if (sessionValue == null || sessionValue.isBlank()) {
            return null;
        }
        int separatorIndex = sessionValue.indexOf(SESSION_VALUE_SEPARATOR);
        if (separatorIndex < 0) {
            return sessionValue;
        }
        return sessionValue.substring(0, separatorIndex);
    }

    private void blacklistToken(String token, Date expiration) {
        if (!StringUtils.hasText(token) || expiration == null) {
            return;
        }
        long ttlSeconds = ChronoUnit.SECONDS.between(new Date().toInstant(), expiration.toInstant());
        if (ttlSeconds <= 0) {
            return;
        }
        stringRedisTemplate.opsForValue().set(
                JwtConstants.BLACKLIST_PREFIX + token,
                "1",
                ttlSeconds,
                TimeUnit.SECONDS
        );
    }

    private boolean isTokenBlacklisted(String token) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(JwtConstants.BLACKLIST_PREFIX + token));
        } catch (Exception e) {
            log.warn("Redis blacklist check failed: {}", e.getMessage());
            return false;
        }
    }
}
