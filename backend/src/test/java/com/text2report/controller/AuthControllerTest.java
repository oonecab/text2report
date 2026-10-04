package com.text2report.controller;

import com.text2report.common.context.TraceFilter;
import com.text2report.config.AuthCookieProperties;
import com.text2report.config.RateLimitFilter;
import com.text2report.config.RequestLoggingFilter;
import com.text2report.domain.meta.entity.SysUser;
import com.text2report.domain.meta.entity.SysUserOrg;
import com.text2report.domain.meta.mapper.SysUserMapper;
import com.text2report.domain.meta.mapper.SysUserOrgMapper;
import com.text2report.common.exception.ErrorCode;
import com.text2report.model.dto.UserDTO;
import com.text2report.security.JwtAuthFilter;
import com.text2report.security.JwtTokenResolver;
import com.text2report.security.JwtUtil;
import com.text2report.service.AuthService;
import com.text2report.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AuthController.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TraceFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RequestLoggingFilter.class)
        }
)
@Import({
        AuthControllerTest.TestSecurityConfig.class,
        AuthService.class,
        JwtUtil.class,
        JwtAuthFilter.class,
        JwtTokenResolver.class,
        AuthCookieProperties.class
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PasswordEncoder passwordEncoder;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    @MockBean
    private SysUserMapper sysUserMapper;
    @MockBean
    private SysUserOrgMapper sysUserOrgMapper;
    @MockBean
    private UserService userService;

    private final Map<String, String> redis = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redis.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            redis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        when(stringRedisTemplate.delete(anyString())).thenAnswer(invocation -> redis.remove(invocation.getArgument(0)) != null);
        when(stringRedisTemplate.hasKey(anyString())).thenAnswer(invocation -> redis.containsKey(invocation.getArgument(0)));

        SysUser user = buildActiveUser();
        when(sysUserMapper.selectByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("secret", "hashed-password")).thenReturn(true);
        when(sysUserOrgMapper.selectPrimaryOrg(1)).thenReturn(buildPrimaryOrg());
        when(userService.getUserById(1)).thenReturn(buildUserDto());
    }

    @Test
    @DisplayName("登录成功后响应体不返回 token 且通过 HttpOnly Cookie 下发")
    void loginShouldSetSecureHttpOnlyCookieWithoutTokenInBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.user.username").value("alice"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, not(blankOrNullString())))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("SameSite=Lax")));
    }

    @Test
    @DisplayName("Cookie 可用于访问 auth/me")
    void shouldAccessMeWithCookie() throws Exception {
        Cookie accessCookie = loginAndExtractCookie();

        mockMvc.perform(get("/api/v1/auth/me").cookie(accessCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    @DisplayName("无 Cookie 访问受保护接口返回 401")
    void shouldReturnUnauthorizedWithoutCookie() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("Bearer Token 在关闭兼容开关时不再被接受")
    void shouldRejectBearerTokenWhenDisabled() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String rawToken = extractCookieValue(loginResult.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("logout 会清除 Cookie 并让旧会话失效")
    void logoutShouldClearCookieAndInvalidateSession() throws Exception {
        Cookie accessCookie = loginAndExtractCookie();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .cookie(accessCookie))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

        mockMvc.perform(get("/api/v1/auth/me").cookie(accessCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("缺少 CSRF 时 logout 返回 403")
    void logoutShouldRequireCsrf() throws Exception {
        Cookie accessCookie = loginAndExtractCookie();

        mockMvc.perform(post("/api/v1/auth/logout").cookie(accessCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("安全校验失败，请刷新页面后重试"));
    }

    @Test
    @DisplayName("CSRF 接口返回可供前端复用的 token")
    void csrfEndpointShouldReturnToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    private Cookie loginAndExtractCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return new Cookie("__Host-t2r_access", extractCookieValue(setCookie));
    }

    private String extractCookieValue(String setCookieHeader) {
        String firstPart = setCookieHeader.split(";", 2)[0];
        return firstPart.substring(firstPart.indexOf('=') + 1);
    }

    private SysUser buildActiveUser() {
        SysUser user = new SysUser();
        user.setId(1);
        user.setUsername("alice");
        user.setPasswordHash("hashed-password");
        user.setRole("ADMIN");
        user.setIsActive(true);
        return user;
    }

    private SysUserOrg buildPrimaryOrg() {
        SysUserOrg userOrg = new SysUserOrg();
        userOrg.setUserId(1);
        userOrg.setOrgId(2);
        userOrg.setIsPrimary(true);
        return userOrg;
    }

    private UserDTO buildUserDto() {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(1);
        userDTO.setUsername("alice");
        userDTO.setDisplayName("Alice");
        userDTO.setRole("ADMIN");
        return userDTO;
    }

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        CookieCsrfTokenRepository csrfTokenRepository() {
            CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
            repository.setCookiePath("/");
            return repository;
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
            http
                    .csrf(csrf -> csrf
                            .csrfTokenRepository(csrfTokenRepository())
                            .ignoringRequestMatchers("/api/v1/auth/login", "/api/v1/auth/csrf")
                    )
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/v1/auth/login", "/api/v1/auth/csrf").permitAll()
                            .anyRequest().authenticated()
                    )
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint((request, response, authException) -> {
                                response.setStatus(401);
                                response.setContentType("application/json;charset=UTF-8");
                                response.getWriter().write("{\"code\":401,\"message\":\"" + ErrorCode.UNAUTHORIZED.getMessage() + "\"}");
                            })
                            .accessDeniedHandler((request, response, accessDeniedException) -> {
                                response.setStatus(403);
                                response.setContentType("application/json;charset=UTF-8");
                                String message = accessDeniedException instanceof org.springframework.security.web.csrf.CsrfException
                                        ? "安全校验失败，请刷新页面后重试"
                                        : ErrorCode.FORBIDDEN.getMessage();
                                response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\"}");
                            })
                    )
                    .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }
    }
}
