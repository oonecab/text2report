package com.text2report.controller;

import com.text2report.common.context.TraceFilter;
import com.text2report.config.RateLimitFilter;
import com.text2report.config.RequestLoggingFilter;
import com.text2report.domain.meta.entity.MetaDatasource;
import com.text2report.security.JwtAuthFilter;
import com.text2report.service.DataSourceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = DataSourceController.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RequestLoggingFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TraceFilter.class)
        }
)
@Import(DataSourceControllerTest.TestSecurityConfig.class)
class DataSourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSourceService dataSourceService;

    @Test
    @DisplayName("ADMIN 可以读取数据源列表")
    void adminCanListActiveDatasources() throws Exception {
        MetaDatasource datasource = new MetaDatasource();
        datasource.setId(1);
        datasource.setName("default");
        datasource.setActive(true);
        when(dataSourceService.listActive()).thenReturn(List.of(datasource));

        mockMvc.perform(get("/api/v1/datasources")
                        .param("activeOnly", "true")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(dataSourceService).listActive();
    }

    @Test
    @DisplayName("MANAGER 可以读取表关系图需要的数据源列表")
    void managerCanListDatasources() throws Exception {
        MetaDatasource datasource = new MetaDatasource();
        datasource.setId(1);
        datasource.setName("default");
        datasource.setActive(true);
        when(dataSourceService.listActive()).thenReturn(List.of(datasource));

        mockMvc.perform(get("/api/v1/datasources")
                        .param("activeOnly", "true")
                        .with(user("wanger").roles("MANAGER")))
                .andExpect(status().isOk());

        verify(dataSourceService).listActive();
    }

    @Test
    @DisplayName("MANAGER 不能创建数据源配置")
    void managerCannotCreateDatasource() throws Exception {
        mockMvc.perform(post("/api/v1/datasources")
                        .with(user("wanger").roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "new-ds",
                                  "dbType": "postgresql",
                                  "jdbcUrl": "jdbc:postgresql://localhost:5432/report",
                                  "username": "readonly"
                                }
                                """))
                .andExpect(status().isForbidden());
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
