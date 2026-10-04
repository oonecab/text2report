package com.text2report.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.ApiResult;
import com.text2report.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring Security configuration with JWT authentication
 *
 * Fix: P1-4 - Added role-based URL authorization
 * Fix: P0-3 - Added AuthenticationEntryPoint and AccessDeniedHandler
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JwtAuthFilter jwtAuthFilter;
    private final RequestLoggingFilter requestLoggingFilter;
    private final RateLimitFilter rateLimitFilter;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${app.cors.allowed-methods}")
    private String allowedMethods;

    @Value("${app.cors.allowed-headers}")
    private String allowedHeaders;

    @Value("${app.cors.max-age:3600}")
    private long corsMaxAge;

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/api/v1/auth/login", "/api/v1/auth/csrf")
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; " +
                                        "script-src 'self'; " +
                                        "object-src 'none'; " +
                                        "base-uri 'self'; " +
                                        "frame-ancestors 'none'; " +
                                        "connect-src 'self'; " +
                                        "img-src 'self' data:; " +
                                        "style-src 'self' 'unsafe-inline'"
                        ))
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(contentTypeOptions -> {})
                        .referrerPolicy(referrer ->
                                referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "未认证，请先登录"))
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            String message = accessDeniedException instanceof CsrfException
                                    ? "安全校验失败，请刷新页面后重试"
                                    : "权限不足";
                            writeError(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN, message);
                        })
                )
                .authorizeHttpRequests(auth -> auth
                        // Fix: P1-4 - Correct CORS preflight exemption
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Public endpoints
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/favicon.ico").permitAll()
                        // Admin-only endpoints - health details
                        .requestMatchers("/api/v1/health/details").hasRole("ADMIN")
                        .requestMatchers("/api/v1/users/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orgs/my-tree").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/orgs/tree").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orgs/*/subtree-ids").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/orgs/*/path").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/orgs/*/users").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orgs/*/users").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/orgs/*/users/*/scope").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/orgs/*/users/*").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orgs/sync").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orgs/sync/status").hasRole("ADMIN")
                        .requestMatchers("/api/v1/embeddings/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orgs").denyAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/orgs/*").denyAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/orgs/*").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/metadata/tables").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/metadata/joins").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/api/v1/audit/**").hasAnyRole("ADMIN", "MANAGER")
                        // Metadata admin endpoints
                        .requestMatchers("/api/v1/metadata/**").hasRole("ADMIN")
                        // Authenticated endpoints
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(requestLoggingFilter, JwtAuthFilter.class)
                .addFilterAfter(rateLimitFilter, RequestLoggingFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(parseCsv(allowedOrigins));
        configuration.setAllowedMethods(parseCsv(allowedMethods));
        configuration.setAllowedHeaders(parseCsv(allowedHeaders));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(corsMaxAge);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void writeError(HttpServletResponse response, int status, ErrorCode errorCode, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ApiResult<Void> body = ApiResult.<Void>error(errorCode, message)
                .withRequestId(TraceContext.getRequestId() != null ? TraceContext.getRequestId() : "unknown");
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(body));
    }

    private List<String> parseCsv(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }
}
