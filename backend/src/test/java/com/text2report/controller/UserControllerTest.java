package com.text2report.controller;

import com.text2report.common.context.TraceFilter;
import com.text2report.common.result.PageResult;
import com.text2report.config.RateLimitFilter;
import com.text2report.config.RequestLoggingFilter;
import com.text2report.model.dto.UserDTO;
import com.text2report.security.JwtAuthFilter;
import com.text2report.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = UserController.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RequestLoggingFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TraceFilter.class)
        }
)
@Import(UserControllerTest.TestSecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    @DisplayName("用户列表接口兼容 active 参数并透传 keyword")
    void listSupportsLegacyActiveParamAndKeyword() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(1);
        user.setUsername("wangsi");
        when(userService.listUsers(null, false, null, "wang", 1, 20, "admin"))
                .thenReturn(PageResult.of(List.of(user), 1, 1, 20));

        mockMvc.perform(get("/api/v1/users")
                        .param("active", "false")
                        .param("keyword", "wang")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(userService).listUsers(null, false, null, "wang", 1, 20, "admin");
    }

    @Test
    @DisplayName("active 和 isActive 冲突时应优先使用 isActive")
    void listPrefersIsActiveWhenParamsConflict() throws Exception {
        when(userService.listUsers(null, true, null, null, 1, 20, "admin"))
                .thenReturn(PageResult.of(List.of(), 0, 1, 20));

        mockMvc.perform(get("/api/v1/users")
                        .param("active", "false")
                        .param("isActive", "true")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(userService).listUsers(null, true, null, null, 1, 20, "admin");
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @org.springframework.context.annotation.Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }
}
