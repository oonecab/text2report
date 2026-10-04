package com.text2report.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.model.dto.ResultProfile;
import com.text2report.service.PromptBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI-compatible LLM Service implementation (supports DeepSeek via OpenAI Chat Completions API)
 *
 * DeepSeek is fully compatible with OpenAI Chat Completions API.
 * Configure base-url and model to switch providers - no code changes needed.
 */
@Slf4j
@Service
public class OpenAILLMService implements LLMService {

    private final ObjectMapper objectMapper;
    private final PromptBuilder promptBuilder;
    private final BgeEmbeddingProvider bgeEmbeddingProvider;

    private LLMAsyncExecutor llmAsyncExecutor;
    private ProviderRoutingResolver providerRoutingResolver;

    @Autowired
    public OpenAILLMService(
            ObjectMapper objectMapper,
            PromptBuilder promptBuilder,
            BgeEmbeddingProvider bgeEmbeddingProvider) {
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.bgeEmbeddingProvider = bgeEmbeddingProvider;
    }

    public OpenAILLMService(ObjectMapper objectMapper, PromptBuilder promptBuilder) {
        this(objectMapper, promptBuilder, null);
    }

    @Autowired
    public void setLlmAsyncExecutor(LLMAsyncExecutor llmAsyncExecutor) {
        this.llmAsyncExecutor = llmAsyncExecutor;
    }

    @Autowired(required = false)
    public void setProviderRoutingResolver(ProviderRoutingResolver providerRoutingResolver) {
        this.providerRoutingResolver = providerRoutingResolver;
    }

    @Override
    public String generateSQL(String question, String contextJson, Integer orgId, String prompt) {
        log.info("Generating SQL for question: {}, orgId: {}", question, orgId);

        String finalPrompt = prompt;
        if (finalPrompt == null || finalPrompt.isBlank()) {
            finalPrompt = promptBuilder.buildSqlGenerationPrompt(
                    "postgresql",
                    orgId != null ? "orgId=" + orgId : "all orgs",
                    orgId != null ? String.valueOf(orgId) : "",
                    contextJson,
                    question
            );
        }

        String rawResponse = callLLMSafely(LlmCapability.SQL_GENERATION, finalPrompt);
        String content = extractContent(rawResponse);

        // Parse JSON response to extract SQL
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode sqlNode = root.get("sql");
            if (sqlNode == null || sqlNode.isNull()) {
                throw new BusinessException(ErrorCode.LLM_PARSE_FAILED, "LLM response missing 'sql' field");
            }
            return sqlNode.asText();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_PARSE_FAILED, "Failed to parse LLM response: " + e.getMessage());
        }
    }

    @Override
    public String summarizeResults(String question, String sql, ResultProfile resultProfile) {
        // Rule 1 Compliance: LLM only sees ResultProfile, never raw data
        log.info("Summarizing results for question: {} with profile: rowCount={}, columns={}",
                question, resultProfile.getRowCount(), resultProfile.getColumnNames());

        try {
            String resultProfileJson = objectMapper.writeValueAsString(resultProfile);
            String prompt = promptBuilder.buildResultSummaryPrompt(question, resultProfileJson);
            String rawResponse = callLLMSafely(LlmCapability.RESULT_SUMMARY, prompt);
            String content = extractContent(rawResponse);

            // Parse JSON response to extract summary
            JsonNode root = objectMapper.readTree(content);
            JsonNode summaryNode = root.get("summary");
            if (summaryNode != null && !summaryNode.isNull()) {
                return summaryNode.asText();
            }
            return "无法生成摘要";
        } catch (Exception e) {
            log.error("Failed to summarize results: {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_PARSE_FAILED, "Failed to summarize: " + e.getMessage());
        }
    }

    @Override
    public String repairSQL(String sql, String errorMessage, String contextJson) {
        log.info("Repairing SQL: {}", sql);

        String prompt = promptBuilder.buildSqlRepairPrompt(
                "postgresql",
                contextJson,
                "", // user question not needed for repair
                sql,
                errorMessage
        );

        String rawResponse = callLLMSafely(LlmCapability.SQL_REPAIR, prompt);
        String content = extractContent(rawResponse);

        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode sqlNode = root.get("sql");
            if (sqlNode != null && !sqlNode.isNull()) {
                String repairedSql = sqlNode.asText();
                if (repairedSql.isEmpty()) {
                    log.warn("LLM returned empty SQL, cannot repair");
                    throw new BusinessException(ErrorCode.LLM_PARSE_FAILED, "LLM could not repair SQL");
                }
                return repairedSql;
            }
            throw new BusinessException(ErrorCode.LLM_PARSE_FAILED, "LLM response missing 'sql' field");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse LLM repair response: {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_PARSE_FAILED, "Failed to parse repair response: " + e.getMessage());
        }
    }

    // I think the method described here is too rigid.
    // Could we use an LLM directly for semantic understanding instead?
    // todo...
    @Override
    public String detectIntent(String question) {
        // Simple keyword-based intent detection
        String lower = question.toLowerCase();
        if (lower.contains("最大") || lower.contains("最小") || lower.contains("最高") || lower.contains("最低")) {
            return "extreme";
        }
        if (lower.contains("趋势") || lower.contains("变化") || lower.contains("增长") || lower.contains("下降")) {
            return "trend";
        }
        if (lower.contains("排名") || lower.contains("前") || lower.contains("后") || lower.contains("最好") || lower.contains("最差")) {
            return "ranking";
        }
        if (lower.contains("汇总") || lower.contains("合计") || lower.contains("总计") || lower.contains("计算")) {
            return "aggregation";
        }
        if (lower.contains("对比") || lower.contains("比较")) {
            return "comparison";
        }
        return "query";
    }

    @Override
    public String recommendChartType(String columns, int rowCount) {
        if (rowCount == 0) return "kpi";
        if (rowCount == 1) return "kpi";

        try {
            JsonNode columnsNode = objectMapper.readTree(columns);
            int colCount = columnsNode.size();

            if (colCount <= 2) return "bar";
            if (colCount <= 4) return "line";
            return "table";
        } catch (Exception e) {
            return "table";
        }
    }

    @Override
    public float[] generateEmbedding(String text) {
        if (bgeEmbeddingProvider == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Embedding provider not configured");
        }
        return bgeEmbeddingProvider.generateEmbedding(text);
    }

    String buildRequestBody(String userPrompt, ActiveProviderContext providerContext) {
        if (providerContext == null || providerContext.getProvider() == null) {
            throw new BusinessException(ErrorCode.LLM_ERROR, "系统尚未配置可用模型，请联系管理员完成模型配置");
        }

        var provider = providerContext.getProvider();
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", provider.getModelCode());
        requestBody.put("messages", new Object[]{
                Map.of("role", "system", "content", buildSystemPrompt()),
                Map.of("role", "user", "content", userPrompt)
        });
        requestBody.put("temperature", provider.getTemperature() != null ? provider.getTemperature() : 0.1);
        requestBody.put("max_tokens", provider.getMaxTokens() != null ? provider.getMaxTokens() : 2000);
        if ("RESPONSE_FORMAT".equalsIgnoreCase(provider.getJsonMode()) || "HYBRID".equalsIgnoreCase(provider.getJsonMode())) {
            requestBody.put("response_format", Map.of("type", "json_object"));
        }
        try {
            return objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to serialize request body");
        }
    }

    private String buildSystemPrompt() {
        return "你是企业财务数据分析助手。请根据用户的问题，返回结构化的 JSON 响应。";
    }

    private String callLLMSafely(LlmCapability capability, String userPrompt) {
        if (providerRoutingResolver == null) {
            throw new BusinessException(ErrorCode.LLM_ERROR, "系统尚未配置可用模型，请联系管理员完成模型配置");
        }

        ActiveProviderContext providerContext = providerRoutingResolver.resolve(capability);
        String requestBody = buildRequestBody(userPrompt, providerContext);
        try {
            return llmAsyncExecutor.callAsync(requestBody, providerContext.getProvider(), providerContext.getSecret()).join();
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof BusinessException) {
                throw (BusinessException) cause;
            }
            if (cause != null) {
                throw new BusinessException(ErrorCode.LLM_ERROR, "LLM call failed: " + cause.getMessage());
            }
            throw new BusinessException(ErrorCode.LLM_ERROR, "LLM call failed: " + e.getMessage());
        }
    }

    /**
     * Extract content from OpenAI Chat Completions response.
     * Falls back to raw response if parsing fails.
     */
    private String extractContent(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_ERROR, "Empty LLM response");
        }

        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            // OpenAI / DeepSeek standard format: choices[0].message.content
            if (root.has("choices") && root.get("choices").isArray() && root.get("choices").size() > 0) {
                JsonNode message = root.get("choices").get(0).get("message");
                if (message != null && message.has("content")) {
                    return cleanJsonContent(message.get("content").asText());
                }
            }

            // Some providers may wrap content directly
            if (root.has("content") && root.get("content").isTextual()) {
                return cleanJsonContent(root.get("content").asText());
            }

            // If it already looks like our expected JSON object, return as-is
            return cleanJsonContent(rawResponse);
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as ChatCompletion JSON, treating as raw content: {}", e.getMessage());
            return cleanJsonContent(rawResponse);
        }
    }

    /**
     * Remove markdown code block wrappers (```json ... ```) if present.
     */
    private String cleanJsonContent(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7).trim();
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3).trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return cleaned;
    }

    /**
     * LLM Response data class
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LLMResponse {
        private String sql;
        private String summary;
        private String explanation;
        private Double confidence;
        private String rawResponse;
        private int promptTokens;
        private int completionTokens;
        private String model;
        private LocalDateTime generatedAt;
    }
}
