package com.text2report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.MetaDatasource;
import com.text2report.domain.meta.mapper.SysUserOrgMapper;
import com.text2report.model.dto.MetadataContext;
import com.text2report.model.dto.OrgTreeNode;
import com.text2report.model.dto.QueryContext;
import com.text2report.model.dto.QueryResultDTO;
import com.text2report.model.dto.ResultProfile;
import com.text2report.service.llm.LLMService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceTest {

    @Mock
    private MetadataService metadataService;
    @Mock
    private OrgService orgService;
    @Mock
    private UserService userService;
    @Mock
    private SysUserOrgMapper sysUserOrgMapper;
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

    private OrchestratorService orchestratorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
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
        QueryExecutionWorkflow queryExecutionWorkflow = new QueryExecutionWorkflow(
                queryExecutorService,
                llmService,
                sqlGenerationWorkflow,
                retryPolicy
        );
        QueryResultAssembler queryResultAssembler = new QueryResultAssembler(
                queryExecutorService,
                llmService,
                new QueryConfidenceService(),
                objectMapper
        );
        orchestratorService = new OrchestratorService(
                queryPlanningService,
                sqlGenerationWorkflow,
                queryExecutionWorkflow,
                queryResultAssembler,
                queryCacheService,
                auditService,
                retryPolicy
        );
        ReflectionTestUtils.setField(orchestratorService, "maxRetries", 2);
        lenient().when(queryCacheService.getCachedResult(anyString(), any())).thenReturn(java.util.Optional.empty());
        lenient().when(userService.resolveAccessibleOrgIds(7)).thenReturn(List.of(2, 5, 6, 100));
        lenient().when(sqlGuardService.validateOrgScope(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(sqlGuardService.enforceOrgScope(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-123");
        traceInfo.setUserId(7);
        traceInfo.setOrgId(2);
        traceInfo.setRole("USER");
        TraceContext.set(traceInfo);
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("Should execute happy path end to end")
    void shouldExecuteHappyPathEndToEnd() {
        stubHappyPath(2);

        QueryResultDTO result = orchestratorService.query("子公司A毛利", 2);

        assertEquals("SELECT org_name FROM dim_org LIMIT 1000", result.getSql());
        assertEquals("summary", result.getSummary());
        assertEquals("kpi", result.getResultProfile().getRecommendedChart());
        assertEquals(1, result.getTable().getRows().size());
        verify(auditService).logQueryStart(any(QueryContext.class));
        verify(auditService).updateAuditLog(any(QueryContext.class), any(QueryResultDTO.class));
        verify(sqlMetadataValidator).validateTableAndColumnExistence("SELECT org_name FROM dim_org LIMIT 1000", 1);
    }

    @Test
    @DisplayName("Should pass recommendation metadata JSON through SQL generation prompt")
    void shouldPassRecommendationMetadataJsonThroughSqlGenerationPrompt() {
        MetaDatasource metaDatasource = new MetaDatasource();
        metaDatasource.setId(1);
        when(metadataService.listActiveDatasources()).thenReturn(List.of(metaDatasource));
        when(llmService.detectIntent("子公司A毛利")).thenReturn("query");
        when(orgService.resolveSubtreeIds(2)).thenReturn(List.of(2, 5));
        when(orgService.resolveOrgPath(2)).thenReturn("总公司 > 子公司A");
        MetadataContext metadataContext = MetadataContext.builder()
                .tableRecommendations(Map.of("fact_gross_profit_detail", "primary"))
                .tableRecommendedBy(Map.of("fact_gross_profit_detail", List.of("glossary:毛利")))
                .build();
        String metadataJson = "{\"tables\":[{\"table_name\":\"fact_gross_profit_detail\",\"recommendation\":\"primary\","
                + "\"recommended_by\":[\"glossary:毛利\"]}]}";
        when(metadataService.retrieveForPrompt("子公司A毛利", 1)).thenReturn(metadataContext);
        when(metadataService.buildMetadataJson(metadataContext)).thenReturn(metadataJson);
        when(promptBuilder.buildSqlGenerationPrompt(anyString(), anyString(), anyString(), eq(metadataJson), eq("子公司A毛利")))
                .thenReturn("prompt-with-recommendations");
        when(llmService.generateSQL("子公司A毛利", metadataJson, 2, "prompt-with-recommendations"))
                .thenReturn("SELECT org_name FROM dim_org");
        when(sqlGuardService.validate("SELECT org_name FROM dim_org"))
                .thenReturn("SELECT org_name FROM dim_org LIMIT 1000");
        stubExecutionSuccess();

        orchestratorService.query("子公司A毛利", 2);

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptBuilder).buildSqlGenerationPrompt(anyString(), anyString(), anyString(), metadataCaptor.capture(), eq("子公司A毛利"));
        assertTrue(metadataCaptor.getValue().contains("\"recommendation\":\"primary\""));
        assertTrue(metadataCaptor.getValue().contains("\"recommended_by\""));
        assertFalse(metadataCaptor.getValue().contains("\"table_name\":\"fact_gross_profit\""));
        verify(llmService).generateSQL("子公司A毛利", metadataJson, 2, "prompt-with-recommendations");
    }

    @Test
    @DisplayName("Should reject unsupported intent without retry")
    void shouldRejectUnsupportedIntentWithoutRetry() {
        when(llmService.detectIntent("导出PDF")).thenReturn("unsupported");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestratorService.query("导出PDF", 2));

        assertEquals("不支持的查询意图", exception.getMessage());
        verify(llmService, never()).repairSQL(anyString(), anyString(), anyString());
        verify(auditService).updateAuditLog(any(QueryContext.class), eq("error"), anyString());
    }

    @Test
    @DisplayName("Should not retry when SqlGuard rejects with policy error")
    void shouldNotRetryWhenSqlGuardRejectsWithPolicyError() {
        stubCommonBeforeGeneration(2);
        when(llmService.generateSQL("删除所有数据", "{}", 2, "prompt")).thenReturn("DELETE FROM dim_org");
        when(sqlGuardService.validate("DELETE FROM dim_org"))
                .thenThrow(new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "DELETE not allowed"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestratorService.query("删除所有数据", 2));

        assertEquals("DELETE not allowed", exception.getMessage());
        verify(llmService, never()).repairSQL(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should block generated SQL without org scope before execution")
    void shouldBlockGeneratedSqlWithoutOrgScopeBeforeExecution() {
        stubCommonBeforeGeneration(2);
        when(llmService.generateSQL("子公司A毛利", "{}", 2, "prompt"))
                .thenReturn("SELECT org_name FROM dim_org");
        when(sqlGuardService.validate("SELECT org_name FROM dim_org"))
                .thenReturn("SELECT org_name FROM dim_org LIMIT 1000");
        when(sqlGuardService.validateOrgScope(eq("SELECT org_name FROM dim_org LIMIT 1000"), anyList()))
                .thenThrow(new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "SQL 缺少组织范围过滤"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestratorService.query("子公司A毛利", 2));

        assertEquals("SQL 缺少组织范围过滤", exception.getMessage());
        verify(queryExecutorService, never()).execute(anyString());
    }

    @Test
    @DisplayName("Should retry after first SqlGuard failure and then succeed")
    void shouldRetryAfterFirstSqlGuardFailureAndThenSucceed() {
        stubCommonBeforeGeneration(2);
        when(llmService.generateSQL("子公司A毛利", "{}", 2, "prompt")).thenReturn("SELECT bad FROM dim_org");
        when(sqlGuardService.validate("SELECT bad FROM dim_org"))
                .thenThrow(new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "column does not exist"));
        when(llmService.repairSQL("SELECT bad FROM dim_org", "column does not exist", "{}"))
                .thenReturn("SELECT org_name FROM dim_org");
        when(sqlGuardService.validate("SELECT org_name FROM dim_org"))
                .thenReturn("SELECT org_name FROM dim_org LIMIT 1000");
        stubExecutionSuccess();

        QueryResultDTO result = orchestratorService.query("子公司A毛利", 2);

        assertEquals(1, result.getAudit().getRetryCount());
        verify(llmService).repairSQL("SELECT bad FROM dim_org", "column does not exist", "{}");
    }

    @Test
    @DisplayName("Should retry after metadata validation failure and then succeed")
    void shouldRetryAfterMetadataValidationFailureAndThenSucceed() {
        stubCommonBeforeGeneration(2);
        when(llmService.generateSQL("子公司A毛利", "{}", 2, "prompt"))
                .thenReturn("SELECT revenue FROM fact_gross_profit");
        when(sqlGuardService.validate("SELECT revenue FROM fact_gross_profit"))
                .thenReturn("SELECT revenue FROM fact_gross_profit LIMIT 1000");
        doThrow(new BusinessException(ErrorCode.METADATA_VALIDATION_FAILED,
                "SQL 引用了未发布或不存在的数据表: fact_gross_profit。已发布的可用表包括: [fact_gross_profit_detail]"))
                .when(sqlMetadataValidator)
                .validateTableAndColumnExistence("SELECT revenue FROM fact_gross_profit LIMIT 1000", 1);
        when(llmService.repairSQL(
                eq("SELECT revenue FROM fact_gross_profit"),
                contains("未发布或不存在的数据表"),
                eq("{}")
        )).thenReturn("SELECT revenue FROM fact_gross_profit_detail");
        when(sqlGuardService.validate("SELECT revenue FROM fact_gross_profit_detail"))
                .thenReturn("SELECT revenue FROM fact_gross_profit_detail LIMIT 1000");

        QueryExecutorService.ExecutionResult repairedResult = QueryExecutorService.ExecutionResult.builder()
                .columns(List.of(QueryExecutorService.ExecutionColumnMeta.builder()
                        .key("revenue")
                        .jdbcType(java.sql.Types.NUMERIC)
                        .jdbcTypeName("NUMERIC")
                        .build()))
                .rows(List.of(Map.of("revenue", 100)))
                .rowCount(1)
                .executionMs(10L)
                .build();
        when(queryExecutorService.execute("SELECT revenue FROM fact_gross_profit_detail LIMIT 1000"))
                .thenReturn(repairedResult);
        when(queryExecutorService.enrichColumnMeta(anyList(), eq(1)))
                .thenReturn(List.of(QueryResultDTO.ColumnMeta.builder().key("revenue").title("收入").dataType("number").build()));
        when(llmService.recommendChartType(anyString(), eq(1))).thenReturn("kpi");
        when(llmService.summarizeResults(anyString(), anyString(), any(ResultProfile.class))).thenReturn("summary");

        QueryResultDTO result = orchestratorService.query("子公司A毛利", 2);

        assertEquals(1, result.getAudit().getRetryCount());
        assertEquals("SELECT revenue FROM fact_gross_profit_detail LIMIT 1000", result.getSql());
        verify(queryExecutorService, never()).execute("SELECT revenue FROM fact_gross_profit LIMIT 1000");
        verify(llmService).repairSQL(
                eq("SELECT revenue FROM fact_gross_profit"),
                contains("已发布的可用表包括"),
                eq("{}")
        );
    }

    @Test
    @DisplayName("Should return generic error after retries exhausted")
    void shouldReturnGenericErrorAfterRetriesExhausted() {
        stubCommonBeforeGeneration(2);
        ReflectionTestUtils.setField(orchestratorService, "maxRetries", 1);
        when(llmService.generateSQL("子公司A毛利", "{}", 2, "prompt")).thenReturn("SELECT bad FROM dim_org");
        when(sqlGuardService.validate("SELECT bad FROM dim_org"))
                .thenThrow(new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "column does not exist"));
        when(llmService.repairSQL("SELECT bad FROM dim_org", "column does not exist", "{}"))
                .thenReturn("SELECT worse FROM dim_org");
        when(sqlGuardService.validate("SELECT worse FROM dim_org"))
                .thenThrow(new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "column does not exist"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestratorService.query("子公司A毛利", 2));

        assertEquals("查询执行失败", exception.getMessage());
    }

    @Test
    @DisplayName("Should bubble generic error when LLM generation fails")
    void shouldBubbleGenericErrorWhenLlmGenerationFails() {
        stubCommonBeforeGeneration(2);
        when(llmService.generateSQL("子公司A毛利", "{}", 2, "prompt"))
                .thenThrow(new BusinessException(ErrorCode.LLM_ERROR, "LLM unavailable"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestratorService.query("子公司A毛利", 2));

        assertEquals("LLM unavailable", exception.getMessage());
    }

    @Test
    @DisplayName("Should retry on retryable execution failure")
    void shouldRetryOnRetryableExecutionFailure() {
        stubCommonBeforeExecution(2);
        when(queryExecutorService.execute("SELECT org_name FROM dim_org LIMIT 1000"))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "column does not exist"));
        when(llmService.repairSQL("SELECT org_name FROM dim_org LIMIT 1000", "column does not exist", "{}"))
                .thenReturn("SELECT org_name, gross_profit FROM dim_org");
        when(sqlGuardService.validate("SELECT org_name, gross_profit FROM dim_org"))
                .thenReturn("SELECT org_name, gross_profit FROM dim_org LIMIT 1000");
        QueryExecutorService.ExecutionResult repairedResult = QueryExecutorService.ExecutionResult.builder()
                .columns(List.of(QueryExecutorService.ExecutionColumnMeta.builder()
                        .key("org_name")
                        .jdbcType(java.sql.Types.VARCHAR)
                        .jdbcTypeName("VARCHAR")
                        .build()))
                .rows(List.of(Map.of("org_name", "子公司A")))
                .rowCount(1)
                .executionMs(20L)
                .build();
        when(queryExecutorService.execute("SELECT org_name, gross_profit FROM dim_org LIMIT 1000"))
                .thenReturn(repairedResult);
        when(queryExecutorService.enrichColumnMeta(anyList(), eq(1)))
                .thenReturn(List.of(QueryResultDTO.ColumnMeta.builder().key("org_name").title("组织名称").dataType("string").build()));
        when(llmService.recommendChartType(anyString(), eq(1))).thenReturn("kpi");
        when(llmService.summarizeResults(anyString(), anyString(), any(ResultProfile.class))).thenReturn("summary");

        QueryResultDTO result = orchestratorService.query("子公司A毛利", 2);

        assertEquals(1, result.getAudit().getRetryCount());
        verify(llmService).repairSQL("SELECT org_name FROM dim_org LIMIT 1000", "column does not exist", "{}");
    }

    @Test
    @DisplayName("Should not retry on non retryable execution failure")
    void shouldNotRetryOnNonRetryableExecutionFailure() {
        stubCommonBeforeExecution(2);
        when(queryExecutorService.execute("SELECT org_name FROM dim_org LIMIT 1000"))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "查询执行失败"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestratorService.query("子公司A毛利", 2));

        assertEquals("查询执行失败", exception.getMessage());
        verify(llmService, never()).repairSQL(eq("SELECT org_name FROM dim_org LIMIT 1000"), anyString(), anyString());
    }

    @Test
    @DisplayName("Should audit both start and failure paths")
    void shouldAuditBothStartAndFailurePaths() {
        when(llmService.detectIntent("异常问题")).thenReturn("unsupported");

        assertThrows(BusinessException.class, () -> orchestratorService.query("异常问题", 2));

        verify(auditService).logQueryStart(any(QueryContext.class));
        verify(auditService).updateAuditLog(any(QueryContext.class), eq("error"), anyString());
    }

    @Test
    @DisplayName("Should pass ResultProfile rather than raw rows to summarizeResults")
    void shouldPassResultProfileRatherThanRawRowsToSummarizeResults() {
        stubHappyPath(2);
        ArgumentCaptor<ResultProfile> resultProfileCaptor = ArgumentCaptor.forClass(ResultProfile.class);

        orchestratorService.query("子公司A毛利", 2);

        verify(llmService).summarizeResults(eq("子公司A毛利"), eq("SELECT org_name FROM dim_org LIMIT 1000"),
                resultProfileCaptor.capture());
        assertEquals(1, resultProfileCaptor.getValue().getRowCount());
        assertEquals(1, resultProfileCaptor.getValue().getColumnCount());
    }

    @Test
    @DisplayName("Should narrow org ids when question mentions an org in selected scope")
    void shouldNarrowOrgIdsWhenQuestionMentionsOrgInSelectedScope() {
        when(userService.resolveAccessibleOrgIds(7)).thenReturn(List.of(2));
        MetaDatasource metaDatasource = new MetaDatasource();
        metaDatasource.setId(1);
        when(metadataService.listActiveDatasources()).thenReturn(List.of(metaDatasource));
        when(llmService.detectIntent("子公司A按月份毛利")).thenReturn("query");
        when(orgService.resolveSubtreeIds(2)).thenReturn(List.of(2, 5, 6));
        when(orgService.resolveOrgPath(2)).thenReturn("总公司 > 子公司A");
        when(orgService.getFullOrgTree()).thenReturn(List.of(OrgTreeNode.builder()
                .id(2)
                .name("子公司A")
                .children(List.of(OrgTreeNode.builder().id(5).name("部门一").build()))
                .build()));
        when(metadataService.retrieveForPrompt(anyString(), eq(1))).thenReturn(new MetadataContext());
        when(metadataService.buildMetadataJson(any(MetadataContext.class))).thenReturn("{}");
        when(promptBuilder.buildSqlGenerationPrompt(anyString(), anyString(), anyString(), anyString(), eq("子公司A按月份毛利")))
                .thenReturn("prompt");
        when(llmService.generateSQL(eq("子公司A按月份毛利"), eq("{}"), eq(2), anyString()))
                .thenReturn("SELECT product_id FROM fact_gross_profit_detail");
        when(sqlGuardService.validate(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + " LIMIT 1000");
        when(sqlGuardService.enforceOrgScope(anyString(), anyList()))
                .thenAnswer(invocation -> "SELECT product_id FROM fact_gross_profit_detail WHERE org_id IN ("
                        + ((List<?>) invocation.getArgument(1)).stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("")
                        + ") LIMIT 1000");
        when(queryExecutorService.execute(anyString())).thenReturn(QueryExecutorService.ExecutionResult.builder()
                .columns(List.of(QueryExecutorService.ExecutionColumnMeta.builder()
                        .key("product_id")
                        .jdbcType(java.sql.Types.INTEGER)
                        .jdbcTypeName("INTEGER")
                        .build()))
                .rows(List.of(Map.of("product_id", 1)))
                .rowCount(1)
                .executionMs(10L)
                .build());
        when(queryExecutorService.enrichColumnMeta(anyList(), eq(1)))
                .thenReturn(List.of(QueryResultDTO.ColumnMeta.builder().key("product_id").title("产品").dataType("number").build()));
        when(llmService.recommendChartType(anyString(), eq(1))).thenReturn("table");
        when(llmService.summarizeResults(anyString(), anyString(), any(ResultProfile.class))).thenReturn("summary");

        QueryResultDTO result = orchestratorService.query("子公司A按月份毛利", 2);

        assertEquals("SELECT product_id FROM fact_gross_profit_detail WHERE org_id IN (2) LIMIT 1000", result.getSql());
        verify(promptBuilder).buildSqlGenerationPrompt(anyString(), anyString(), eq("2"), anyString(), eq("子公司A按月份毛利"));
    }

    @Test
    @DisplayName("Should propagate requestId into QueryContext")
    void shouldPropagateRequestIdIntoQueryContext() {
        stubHappyPath(2);
        ArgumentCaptor<QueryContext> queryContextCaptor = ArgumentCaptor.forClass(QueryContext.class);

        orchestratorService.query("子公司A毛利", 2);

        verify(auditService).logQueryStart(queryContextCaptor.capture());
        assertEquals("req-123", queryContextCaptor.getValue().getRequestId());
    }

    @Test
    @DisplayName("Should resolve orgId from TraceContext when request orgId is null")
    void shouldResolveOrgIdFromTraceContextWhenRequestOrgIdIsNull() {
        stubHappyPath(2);

        orchestratorService.query("子公司A毛利", null);

        verify(orgService).resolveSubtreeIds(2);
    }

    @Test
    @DisplayName("Should throw validation error when orgId is null and TraceContext has no org")
    void shouldThrowValidationErrorWhenOrgIdIsNullAndTraceContextHasNoOrg() {
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-456");
        traceInfo.setUserId(7);
        TraceContext.set(traceInfo);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestratorService.query("子公司A毛利", null));

        assertEquals("orgId 不能为空", exception.getMessage());
    }

    private void stubHappyPath(Integer orgId) {
        stubCommonBeforeExecution(orgId);
        stubExecutionSuccess();
    }

    private void stubCommonBeforeExecution(Integer orgId) {
        stubCommonBeforeGeneration(orgId);
        when(llmService.generateSQL("子公司A毛利", "{}", orgId, "prompt")).thenReturn("SELECT org_name FROM dim_org");
        when(sqlGuardService.validate("SELECT org_name FROM dim_org"))
                .thenReturn("SELECT org_name FROM dim_org LIMIT 1000");
    }

    private void stubExecutionSuccess() {
        QueryExecutorService.ExecutionResult executionResult = QueryExecutorService.ExecutionResult.builder()
                .columns(List.of(QueryExecutorService.ExecutionColumnMeta.builder()
                        .key("org_name")
                        .jdbcType(java.sql.Types.VARCHAR)
                        .jdbcTypeName("VARCHAR")
                        .build()))
                .rows(List.of(Map.of("org_name", "子公司A")))
                .rowCount(1)
                .executionMs(12L)
                .build();
        when(queryExecutorService.execute("SELECT org_name FROM dim_org LIMIT 1000")).thenReturn(executionResult);
        when(queryExecutorService.enrichColumnMeta(anyList(), eq(1)))
                .thenReturn(List.of(QueryResultDTO.ColumnMeta.builder().key("org_name").title("组织名称").dataType("string").build()));
        when(llmService.recommendChartType(anyString(), eq(1))).thenReturn("kpi");
        when(llmService.summarizeResults(anyString(), anyString(), any(ResultProfile.class))).thenReturn("summary");
    }

    private void stubCommonBeforeGeneration(Integer orgId) {
        MetaDatasource metaDatasource = new MetaDatasource();
        metaDatasource.setId(1);
        when(metadataService.listActiveDatasources()).thenReturn(List.of(metaDatasource));
        when(llmService.detectIntent(anyString())).thenReturn("query");
        when(orgService.resolveSubtreeIds(orgId)).thenReturn(List.of(orgId, 5));
        when(orgService.resolveOrgPath(orgId)).thenReturn("总公司 > 子公司A");
        when(metadataService.retrieveForPrompt(anyString(), eq(1))).thenReturn(new MetadataContext());
        when(metadataService.buildMetadataJson(any(MetadataContext.class))).thenReturn("{}");
        when(promptBuilder.buildSqlGenerationPrompt(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("prompt");
    }
}
