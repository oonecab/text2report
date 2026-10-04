package com.text2report.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.context.TraceContext;
import com.text2report.common.context.TraceFilter;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.config.RateLimitFilter;
import com.text2report.config.RequestLoggingFilter;
import com.text2report.domain.meta.entity.MetaDatasource;
import com.text2report.model.dto.MetadataContext;
import com.text2report.model.dto.QueryContext;
import com.text2report.model.dto.QueryRequest;
import com.text2report.model.dto.QueryResultDTO;
import com.text2report.model.dto.ResultProfile;
import com.text2report.model.dto.FavoriteItem;
import com.text2report.security.JwtAuthFilter;
import com.text2report.service.AuditService;
import com.text2report.service.FavoriteService;
import com.text2report.service.FeedbackService;
import com.text2report.service.HistoryService;
import com.text2report.service.MetadataService;
import com.text2report.service.OrgService;
import com.text2report.service.OrchestratorService;
import com.text2report.service.PromptBuilder;
import com.text2report.service.QueryCacheService;
import com.text2report.service.QueryConfidenceService;
import com.text2report.service.QueryExecutionWorkflow;
import com.text2report.service.QueryExecutorService;
import com.text2report.service.QueryPlanningService;
import com.text2report.service.QueryResultAssembler;
import com.text2report.service.SqlGenerationWorkflow;
import com.text2report.service.SqlGuardService;
import com.text2report.service.SqlMetadataValidator;
import com.text2report.service.SqlRetryPolicy;
import com.text2report.service.SqlValidationPipeline;
import com.text2report.service.UserService;
import com.text2report.service.llm.LLMService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = QueryController.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RequestLoggingFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TraceFilter.class)
        }
)
@Import(QueryControllerTest.TestSecurityConfig.class)
class QueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrchestratorService orchestratorService;
    @MockBean
    private HistoryService historyService;
    @MockBean
    private FeedbackService feedbackService;
    @MockBean
    private FavoriteService favoriteService;

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("query_有权限_返回200和查询结果")
    void query_有权限_返回200和查询结果() throws Exception {
        // given
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-query-1");
        traceInfo.setRole("ADMIN");
        TraceContext.set(traceInfo);
        when(orchestratorService.query("本月毛利", 100))
                .thenReturn(buildQueryResult("SELECT * FROM profit_view LIMIT 1000"));

        // when
        mockMvc.perform(post("/api/v1/query")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "本月毛利",
                                  "orgId": 100
                                }
                                """))
                // then
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.request_id").value("req-1"))
                .andExpect(jsonPath("$.data.is_favorited").value(false))
                .andExpect(jsonPath("$.data.summary").value("毛利整体稳定"))
                .andExpect(jsonPath("$.data.table.rows[0].org_name").value("子公司A"));
    }

    @Test
    @DisplayName("query_非管理员隐藏SQL但保留收藏状态")
    void query_非管理员隐藏SQL但保留收藏状态() throws Exception {
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-query-2");
        traceInfo.setRole("USER");
        TraceContext.set(traceInfo);
        when(orchestratorService.query("本月毛利", 100))
                .thenReturn(buildQueryResult("SELECT * FROM profit_view LIMIT 1000", true));

        mockMvc.perform(post("/api/v1/query")
                        .with(user("demo").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "本月毛利",
                                  "orgId": 100
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sql").isEmpty())
                .andExpect(jsonPath("$.data.is_favorited").value(true));
    }

    @Test
    @DisplayName("query_用户无该组织权限_返回403")
    void query_用户无该组织权限_返回403() throws Exception {
        // given
        when(orchestratorService.query("本月毛利", 100))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "您没有该组织的查询权限"));

        // when
        mockMvc.perform(post("/api/v1/query")
                        .with(user("demo").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "本月毛利",
                                  "orgId": 100
                                }
                                """))
                // then
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("您没有该组织的查询权限"));
    }

    @Test
    @DisplayName("query_LLM超时_返回502")
    void query_LLM超时_返回502() throws Exception {
        // given
        when(orchestratorService.query("本月毛利", 100))
                .thenThrow(new BusinessException(ErrorCode.LLM_TIMEOUT, "LLM调用超时"));

        // when
        mockMvc.perform(post("/api/v1/query")
                        .with(user("demo").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "本月毛利",
                                  "orgId": 100
                                }
                                """))
                // then
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(ErrorCode.LLM_TIMEOUT.getCode()))
                .andExpect(jsonPath("$.message").value("LLM调用超时"));
    }

    @Test
    @DisplayName("query_SqlGuard拦截_返回4002")
    void query_SqlGuard拦截_返回4002() throws Exception {
        // given
        when(orchestratorService.query("本月毛利", 100))
                .thenThrow(new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "SQL安全校验拒绝"));

        // when
        mockMvc.perform(post("/api/v1/query")
                        .with(user("demo").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "本月毛利",
                                  "orgId": 100
                                }
                                """))
                // then
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4002))
                .andExpect(jsonPath("$.message").value("SQL安全校验拒绝"));
    }

    @Test
    @DisplayName("getFavorites_返回当前用户收藏列表")
    void getFavorites_返回当前用户收藏列表() throws Exception {
        when(favoriteService.getFavorites()).thenReturn(List.of(
                FavoriteItem.builder()
                        .requestId("req-fav-1")
                        .question("本月毛利")
                        .orgId(100)
                        .orgName("华夏集团")
                        .recommendedChart("bar")
                        .rowCount(5)
                        .build()
        ));

        mockMvc.perform(get("/api/v1/query/favorites")
                        .with(user("demo").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].request_id").value("req-fav-1"))
                .andExpect(jsonPath("$.data[0].question").value("本月毛利"));
    }

    @Test
    @DisplayName("addFavorite_调用服务并返回200")
    void addFavorite_调用服务并返回200() throws Exception {
        mockMvc.perform(post("/api/v1/query/req-fav-1/favorite")
                        .with(user("demo").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(favoriteService).addFavorite("req-fav-1");
    }

    @Test
    @DisplayName("removeFavorite_调用服务并返回200")
    void removeFavorite_调用服务并返回200() throws Exception {
        mockMvc.perform(delete("/api/v1/query/req-fav-1/favorite")
                        .with(user("demo").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(favoriteService).removeFavorite("req-fav-1");
    }

    private QueryResultDTO buildQueryResult(String sql) {
        return buildQueryResult(sql, false);
    }

    private QueryResultDTO buildQueryResult(String sql, boolean favorited) {
        return QueryResultDTO.builder()
                .requestId("req-1")
                .sql(sql)
                .isFavorited(favorited)
                .table(QueryResultDTO.TableData.builder()
                        .columns(List.of(
                                QueryResultDTO.ColumnMeta.builder()
                                        .key("org_name")
                                        .title("组织")
                                        .dataType("string")
                                        .build()
                        ))
                        .rows(List.of(Map.of("org_name", "子公司A")))
                        .build())
                .resultProfile(ResultProfile.builder()
                        .recommendedChart("bar")
                        .rowCount(1)
                        .columnCount(1)
                        .columnNames(List.of("org_name"))
                        .hasData(true)
                        .build())
                .summary("毛利整体稳定")
                .warnings(List.of())
                .audit(QueryResultDTO.AuditInfo.builder()
                        .confidence(new BigDecimal("0.90"))
                        .retryCount(0)
                        .executionMs(88L)
                        .build())
                .build();
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

    @Nested
    @ExtendWith(MockitoExtension.class)
    class CachePathTest {

        @Mock
        private MetadataService metadataService;
        @Mock
        private OrgService orgService;
        @Mock
        private UserService userService;
        @Mock
        private com.text2report.domain.meta.mapper.SysUserOrgMapper sysUserOrgMapper;
        @Mock
        private LLMService llmService;
        @Mock
        private SqlGuardService sqlGuardService;
        @Mock
        private SqlMetadataValidator sqlMetadataValidator;
        @Mock
        private QueryExecutorService queryExecutorService;
        @Mock
        private QueryCacheService queryCacheService;
        @Mock
        private AuditService auditService;
        @Mock
        private PromptBuilder promptBuilder;

        @InjectMocks
        private OrchestratorService realOrchestratorService;

        @Test
        @DisplayName("query_命中Redis缓存_直接返回缓存结果且不执行后续编排")
        void query_命中Redis缓存_直接返回缓存结果且不执行后续编排() {
            // given
            ObjectMapper mapper = new ObjectMapper();
            SqlRetryPolicy retryPolicy = new SqlRetryPolicy();
            QueryPlanningService queryPlanningService = new QueryPlanningService(
                    metadataService,
                    orgService,
                    userService,
                    sysUserOrgMapper,
                    llmService
            );
            SqlValidationPipeline sqlValidationPipeline = new SqlValidationPipeline(
                    sqlGuardService,
                    sqlMetadataValidator
            );
            SqlGenerationWorkflow sqlGenerationWorkflow = new SqlGenerationWorkflow(
                    llmService,
                    promptBuilder,
                    sqlValidationPipeline,
                    retryPolicy
            );
            realOrchestratorService = new OrchestratorService(
                    queryPlanningService,
                    sqlGenerationWorkflow,
                    new QueryExecutionWorkflow(queryExecutorService, llmService, sqlGenerationWorkflow, retryPolicy),
                    new QueryResultAssembler(queryExecutorService, llmService, new QueryConfidenceService(), mapper),
                    queryCacheService,
                    auditService,
                    retryPolicy
            );
            TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-cache-1");
            traceInfo.setUserId(9);
            traceInfo.setOrgId(100);
            traceInfo.setRole("USER");
            TraceContext.set(traceInfo);
            QueryResultDTO cached = buildQueryResult("SELECT cached");
            when(queryCacheService.getCachedResult("本月毛利", 100)).thenReturn(Optional.of(cached));

            // when
            QueryResultDTO result = realOrchestratorService.query("本月毛利", 100);

            // then
            assertEquals("req-cache-1", result.getRequestId());
            verify(queryCacheService).getCachedResult("本月毛利", 100);
            verify(auditService).logQueryStart(any(QueryContext.class));
            verify(auditService).updateAuditLogFromCache(any(QueryContext.class), eq(cached));
            verify(metadataService, never()).listActiveDatasources();
            verify(llmService, never()).generateSQL(anyString(), anyString(), anyInt(), anyString());
            verify(queryExecutorService, never()).execute(anyString());
        }
    }
}
