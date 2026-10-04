package com.text2report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.MetaLlmProvider;
import com.text2report.model.dto.ResultProfile;
import com.text2report.service.llm.ActiveProviderContext;
import com.text2report.service.llm.LLMAsyncExecutor;
import com.text2report.service.llm.LlmCapability;
import com.text2report.service.llm.OpenAILLMService;
import com.text2report.service.llm.ProviderRoutingResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OpenAILLMService
 */
@ExtendWith(MockitoExtension.class)
class OpenAILLMServiceTest {

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private LLMAsyncExecutor llmAsyncExecutor;

    @Mock
    private ProviderRoutingResolver providerRoutingResolver;

    private MetaLlmProvider provider;

    private ActiveProviderContext providerContext;

    private ObjectMapper objectMapper = new ObjectMapper();

    private OpenAILLMService openAILLMService;

    @BeforeEach
    void setUp() {
        provider = new MetaLlmProvider();
        provider.setProviderKey("deepseek-default");
        provider.setBaseUrl("https://api.deepseek.com/v1");
        provider.setModelCode("deepseek-chat");
        provider.setTemperature(BigDecimal.valueOf(0.1));
        provider.setMaxTokens(2000);
        provider.setTimeoutSeconds(60);
        provider.setJsonMode("RESPONSE_FORMAT");

        providerContext = ActiveProviderContext.builder()
                .capability(LlmCapability.SQL_GENERATION)
                .provider(provider)
                .secret("test-key")
                .build();

        lenient().when(providerRoutingResolver.resolve(any(LlmCapability.class))).thenReturn(providerContext);

        openAILLMService = new OpenAILLMService(objectMapper, promptBuilder);
        openAILLMService.setLlmAsyncExecutor(llmAsyncExecutor);
        openAILLMService.setProviderRoutingResolver(providerRoutingResolver);
    }

    @Nested
    @DisplayName("detectIntent")
    class DetectIntentTests {

        @Test
        @DisplayName("Should detect extreme intent for max/min queries")
        void shouldDetectExtremeIntent() {
            assertEquals("extreme", openAILLMService.detectIntent("找出销售额最大的产品"));
            assertEquals("extreme", openAILLMService.detectIntent("哪个部门利润最低"));
        }

        @Test
        @DisplayName("Should detect trend intent")
        void shouldDetectTrendIntent() {
            assertEquals("trend", openAILLMService.detectIntent("分析销售额的变化趋势"));
            assertEquals("trend", openAILLMService.detectIntent("收入增长情况"));
        }

        @Test
        @DisplayName("Should detect ranking intent")
        void shouldDetectRankingIntent() {
            assertEquals("ranking", openAILLMService.detectIntent("排名前十的销售"));
            assertEquals("ranking", openAILLMService.detectIntent("哪个区域销售最好"));
        }

        @Test
        @DisplayName("Should detect aggregation intent")
        void shouldDetectAggregationIntent() {
            assertEquals("aggregation", openAILLMService.detectIntent("汇总所有收入"));
            assertEquals("aggregation", openAILLMService.detectIntent("计算总毛利"));
        }

        @Test
        @DisplayName("Should default to query intent")
        void shouldDefaultToQuery() {
            assertEquals("query", openAILLMService.detectIntent("查询数据"));
        }
    }

    @Nested
    @DisplayName("recommendChartType")
    class RecommendChartTypeTests {

        @Test
        @DisplayName("Should return kpi for zero rows")
        void shouldReturnKpiForZeroRows() {
            assertEquals("kpi", openAILLMService.recommendChartType("[]", 0));
        }

        @Test
        @DisplayName("Should return kpi for single row")
        void shouldReturnKpiForSingleRow() {
            assertEquals("kpi", openAILLMService.recommendChartType("[]", 1));
        }

        @Test
        @DisplayName("Should return bar for few columns")
        void shouldReturnBarForFewColumns() throws Exception {
            String columns = "[\"col1\", \"col2\"]";
            assertEquals("bar", openAILLMService.recommendChartType(columns, 10));
        }

        @Test
        @DisplayName("Should return table for many columns")
        void shouldReturnTableForManyColumns() throws Exception {
            String columns = "[\"col1\", \"col2\", \"col3\", \"col4\", \"col5\"]";
            assertEquals("table", openAILLMService.recommendChartType(columns, 10));
        }
    }

    @Nested
    @DisplayName("LLMResponse parsing")
    class LLMResponseTests {

        @Test
        @DisplayName("Should build request body correctly via reflection")
        void shouldBuildRequestBody() throws Exception {
            // Access package-private method via reflection
            var method = OpenAILLMService.class.getDeclaredMethod("buildRequestBody", String.class, ActiveProviderContext.class);
            method.setAccessible(true);

            String body = (String) method.invoke(openAILLMService, "test prompt", providerContext);
            assertNotNull(body);
            assertTrue(body.contains("\"model\":\"deepseek-chat\""));
            assertTrue(body.contains("\"temperature\":0.1"));
            assertTrue(body.contains("test prompt"));
        }

        @Test
        @DisplayName("Should throw BusinessException on HTTP 5xx error")
        void shouldThrowOnHttpError() {
            when(llmAsyncExecutor.callAsync(any(String.class), any(MetaLlmProvider.class), any()))
                    .thenReturn(CompletableFuture.failedFuture(
                            new BusinessException(ErrorCode.LLM_ERROR, "HTTP 500 Internal Server Error")));

            // The exception is wrapped in CompletionException
            Exception ex = assertThrows(Exception.class,
                    () -> openAILLMService.generateSQL("测试问题", "{}", 1, "test prompt"));
            assertTrue(ex.getCause() instanceof BusinessException ||
                    ex instanceof BusinessException);
        }

        @Test
        @DisplayName("Should throw BusinessException on invalid JSON response")
        void shouldThrowOnInvalidJson() {
            when(llmAsyncExecutor.callAsync(any(String.class), any(MetaLlmProvider.class), any()))
                    .thenReturn(CompletableFuture.completedFuture("这不是JSON格式的回复"));

            assertThrows(BusinessException.class,
                    () -> openAILLMService.generateSQL("测试问题", "{}", 1, "test prompt"));
        }

        @Test
        @DisplayName("Should verify summarizeResults takes ResultProfile (Rule 1 compliance)")
        void shouldVerifySummarizeResultsSignature() throws NoSuchMethodException {
            // Verify that summarizeResults takes ResultProfile, not raw data
            var method = OpenAILLMService.class.getMethod("summarizeResults",
                    String.class, String.class, ResultProfile.class);
            assertEquals(ResultProfile.class, method.getParameterTypes()[2]);
        }
    }
}
