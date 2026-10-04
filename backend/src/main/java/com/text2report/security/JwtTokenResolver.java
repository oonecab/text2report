package com.text2report.security;

import com.text2report.config.AuthCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class JwtTokenResolver {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthCookieProperties authCookieProperties;

    @Value("${app.security.allow-bearer-token:false}")
    private boolean allowBearerToken;

    public ResolvedToken resolve(HttpServletRequest request) {
        String cookieToken = extractFromCookie(request);
        if (StringUtils.hasText(cookieToken)) {
            return new ResolvedToken(cookieToken, TokenSource.COOKIE, false);
        }

        String authHeader = request.getHeader(AUTH_HEADER);
        if (!StringUtils.hasText(authHeader)) {
            return ResolvedToken.missing();
        }
        if (!authHeader.startsWith(BEARER_PREFIX)) {
            return new ResolvedToken(null, TokenSource.NONE, true);
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            return new ResolvedToken(null, TokenSource.NONE, true);
        }
        if (!allowBearerToken) {
            return new ResolvedToken(null, TokenSource.BEARER, true);
        }
        return new ResolvedToken(token, TokenSource.BEARER, false);
    }

    public String resolveToken(HttpServletRequest request) {
        ResolvedToken resolvedToken = resolve(request);
        return resolvedToken.malformed() ? null : resolvedToken.token();
    }

    private String extractFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (authCookieProperties.getResolvedAccessCookieName().equals(cookie.getName())
                    && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public enum TokenSource {
        COOKIE,
        BEARER,
        NONE
    }

    public record ResolvedToken(String token, TokenSource source, boolean malformed) {
        public static ResolvedToken missing() {
            return new ResolvedToken(null, TokenSource.NONE, false);
        }
    }
}
