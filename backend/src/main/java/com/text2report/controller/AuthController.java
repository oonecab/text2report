package com.text2report.controller;

import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.ApiResult;
import com.text2report.config.AuthCookieProperties;
import com.text2report.model.dto.UserDTO;
import com.text2report.security.JwtTokenResolver;
import com.text2report.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookieProperties authCookieProperties;
    private final JwtTokenResolver jwtTokenResolver;

    @PostMapping("/login")
    public ApiResult<AuthService.LoginResponse> login(@RequestBody LoginRequest request,
                                                      HttpServletResponse response) {
        AuthService.LoginResponse loginResponse = authService.login(request.getUsername(), request.getPassword());
        response.addHeader(HttpHeaders.SET_COOKIE, buildAccessCookie(loginResponse.getAccessToken(), loginResponse.getExpiresIn()).toString());
        loginResponse.setAccessToken(null);
        return ApiResult.success(loginResponse)
                .withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(jwtTokenResolver.resolveToken(request));
        response.addHeader(HttpHeaders.SET_COOKIE, buildClearedAccessCookie().toString());
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/validate")
    public ApiResult<Boolean> validateToken(HttpServletRequest request) {
        return ApiResult.success(authService.validateToken(jwtTokenResolver.resolveToken(request)))
                .withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/me")
    public ApiResult<UserDTO> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或Token失效");
        }
        return ApiResult.success(authService.getCurrentUser(authentication.getName()))
                .withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/csrf")
    public ApiResult<CsrfTokenResponse> csrf(CsrfToken csrfToken) {
        String token = csrfToken.getToken();
        return ApiResult.success(new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getParameterName(), token))
                .withRequestId(TraceContext.getRequestId());
    }

    private ResponseCookie buildAccessCookie(String token, long maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(authCookieProperties.getResolvedAccessCookieName(), token)
                .httpOnly(true)
                .secure(authCookieProperties.isSecure())
                .sameSite(authCookieProperties.getSameSite())
                .path(authCookieProperties.getPath())
                .maxAge(maxAgeSeconds);
        if (StringUtils.hasText(authCookieProperties.getDomain())) {
            builder.domain(authCookieProperties.getDomain());
        }
        return builder.build();
    }

    private ResponseCookie buildClearedAccessCookie() {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(authCookieProperties.getResolvedAccessCookieName(), "")
                .httpOnly(true)
                .secure(authCookieProperties.isSecure())
                .sameSite(authCookieProperties.getSameSite())
                .path(authCookieProperties.getPath())
                .maxAge(0);
        if (StringUtils.hasText(authCookieProperties.getDomain())) {
            builder.domain(authCookieProperties.getDomain());
        }
        return builder.build();
    }

    @lombok.Data
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @lombok.Value
    public static class CsrfTokenResponse {
        String headerName;
        String parameterName;
        String token;
    }
}
