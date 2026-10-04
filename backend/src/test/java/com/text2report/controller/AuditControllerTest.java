package com.text2report.controller;

import com.text2report.common.result.PageResult;
import com.text2report.common.context.TraceFilter;
import com.text2report.config.RateLimitFilter;
import com.text2report.config.RequestLoggingFilter;
import com.text2report.model.dto.AuditLogDetailVO;
import com.text2report.model.dto.AuditLogVO;
import com.text2report.model.dto.AuditStatsVO;
import com.text2report.security.JwtAuthFilter;
import com.text2report.service.AuditQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AuditController.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RequestLoggingFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TraceFilter.class)
        }
)
@Import(AuditControllerTest.TestSecurityConfig.class)
class AuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditQueryService auditQueryService;

    @Test
    @DisplayName("getLogs_admin角色_返回200和分页数据")
    void getLogs_admin角色_返回200和分页数据() throws Exception {
        // given
        when(auditQueryService.getAuditLogs(eq(1), eq(10), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(PageResult.of(List.of(
                        AuditLogVO.builder()
                                .requestId("req-1")
                                .displayName("系统管理员")
                                .orgName("总公司")
                                .question("本月毛利")
                                .status("success")
                                .latencyMs(1200L)
                                .rowCount(3)
                                .confidence(new BigDecimal("0.88"))
                                .createdAt(LocalDateTime.of(2026, 4, 12, 10, 0))
                                .build()
                ), 1, 1, 10));

        // when
        mockMvc.perform(get("/api/v1/audit/logs").with(user("admin").roles("ADMIN")))
                // then
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].requestId").value("req-1"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("getStats_admin角色_返回统计数据")
    void getStats_admin角色_返回统计数据() throws Exception {
        // given
        when(auditQueryService.getAuditStats(eq(30), any()))
                .thenReturn(AuditStatsVO.builder()
                        .totalQueries(30)
                        .successCount(27)
                        .failedCount(3)
                        .avgExecutionMs(420)
                        .avgLatencyMs(420)
                        .avgConfidence(new BigDecimal("0.86"))
                        .successRate(new BigDecimal("90.00"))
                        .cacheHitRate(new BigDecimal("25.00"))
                        .topUsers(List.of())
                        .dailyTrend(List.of())
                        .build());

        // when
        mockMvc.perform(get("/api/v1/audit/stats").with(user("admin").roles("ADMIN")))
                // then
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalQueries").value(30))
                .andExpect(jsonPath("$.data.avgLatencyMs").value(420));
    }

    @Test
    @DisplayName("getLogs_manager角色_返回200和分页数据")
    void getLogs_manager角色_返回200和分页数据() throws Exception {
        when(auditQueryService.getAuditLogs(eq(1), eq(10), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(PageResult.of(List.of(
                        AuditLogVO.builder()
                                .requestId("req-manager")
                                .displayName("子公司管理员")
                                .orgName("子公司A")
                                .question("本月毛利")
                                .status("success")
                                .latencyMs(800L)
                                .rowCount(2)
                                .confidence(new BigDecimal("0.90"))
                                .createdAt(LocalDateTime.of(2026, 4, 12, 11, 0))
                                .build()
                ), 1, 1, 10));

        mockMvc.perform(get("/api/v1/audit/logs").with(user("wanger").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].requestId").value("req-manager"));
    }

    @Test
    @DisplayName("getStats_manager角色_返回统计数据")
    void getStats_manager角色_返回统计数据() throws Exception {
        when(auditQueryService.getAuditStats(eq(30), any()))
                .thenReturn(AuditStatsVO.builder()
                        .totalQueries(10)
                        .successCount(9)
                        .failedCount(1)
                        .avgExecutionMs(360)
                        .avgLatencyMs(360)
                        .avgConfidence(new BigDecimal("0.83"))
                        .successRate(new BigDecimal("90.00"))
                        .cacheHitRate(new BigDecimal("10.00"))
                        .topUsers(List.of())
                        .dailyTrend(List.of())
                        .build());

        mockMvc.perform(get("/api/v1/audit/stats").with(user("wanger").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQueries").value(10));
    }

    @Test
    @DisplayName("getLogs_普通user角色访问_返回403")
    void getLogs_普通user角色访问_返回403() throws Exception {
        // given

        // when
        mockMvc.perform(get("/api/v1/audit/logs").with(user("demo").roles("USER")))
                // then
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("getLogs_带筛选条件_正确透传分页和日期范围")
    void getLogs_带筛选条件_正确透传分页和日期范围() throws Exception {
        // given
        when(auditQueryService.getAuditLogs(eq(2), eq(20), eq(1), eq(2), eq("success"),
                any(), any(), any(), any(), any()))
                .thenReturn(PageResult.of(List.of(), 0, 2, 20));

        // when
        mockMvc.perform(get("/api/v1/audit/logs")
                        .param("page", "2")
                        .param("pageSize", "20")
                        .param("userId", "1")
                        .param("orgId", "2")
                        .param("status", "success")
                        .param("startDate", "2026-04-01")
                        .param("endDate", "2026-04-10")
                        .with(user("admin").roles("ADMIN")))
                // then
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditQueryService).getAuditLogs(eq(2), eq(20), eq(1), eq(2), eq("success"),
                startCaptor.capture(), endCaptor.capture(), any(), any(), eq("admin"));
        assertEquals(LocalDate.of(2026, 4, 1).atStartOfDay(), startCaptor.getValue());
        assertEquals(LocalDate.of(2026, 4, 11).atStartOfDay(), endCaptor.getValue());
    }

    @Test
    @DisplayName("getDetail_存在的requestId_返回详情")
    void getDetail_存在的requestId_返回详情() throws Exception {
        // given
        when(auditQueryService.getAuditLogDetail(eq("req-9"), any()))
                .thenReturn(AuditLogDetailVO.builder()
                        .requestId("req-9")
                        .fullSql("SELECT 1")
                        .status("success")
                        .warnings(List.of())
                        .build());

        // when
        mockMvc.perform(get("/api/v1/audit/logs/req-9").with(user("admin").roles("ADMIN")))
                // then
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requestId").value("req-9"))
                .andExpect(jsonPath("$.data.fullSql").value("SELECT 1"));
    }

    @Test
    @DisplayName("getDetail_不存在的requestId_返回404")
    void getDetail_不存在的requestId_返回404() throws Exception {
        // given
        when(auditQueryService.getAuditLogDetail(eq("req-missing"), any()))
                .thenThrow(new com.text2report.common.exception.BusinessException(
                        com.text2report.common.exception.ErrorCode.NOT_FOUND,
                        "审计日志不存在"));

        // when
        mockMvc.perform(get("/api/v1/audit/logs/req-missing").with(user("admin").roles("ADMIN")))
                // then
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("审计日志不存在"));
    }

    @Test
    @DisplayName("getStats_空统计结果_返回默认值")
    void getStats_空统计结果_返回默认值() throws Exception {
        // given
        when(auditQueryService.getAuditStats(eq(7), any()))
                .thenReturn(AuditStatsVO.builder()
                        .totalQueries(0)
                        .successCount(0)
                        .failedCount(0)
                        .avgExecutionMs(0)
                        .avgLatencyMs(0)
                        .avgConfidence(new BigDecimal("0.00"))
                        .successRate(new BigDecimal("0.00"))
                        .cacheHitRate(new BigDecimal("0.00"))
                        .topUsers(List.of())
                        .dailyTrend(List.of())
                        .build());

        // when
        mockMvc.perform(get("/api/v1/audit/stats").param("days", "7").with(user("admin").roles("ADMIN")))
                // then
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalQueries").value(0))
                .andExpect(jsonPath("$.data.failedCount").value(0));
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }
}
