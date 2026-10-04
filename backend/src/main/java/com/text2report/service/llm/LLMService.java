package com.text2report.service.llm;

import com.text2report.model.dto.ResultProfile;
import lombok.Data;

import java.util.Map;

/**
 * LLM Service interface - never call LLM SDKs directly from orchestration code
 *
 * Rule 6: Interface-driven design - switching providers only requires config change
 */
public interface LLMService {

    /**
     * Generate SQL from natural language question
     *
     * @param question    Natural language question
     * @param contextJson Metadata context JSON
     * @param orgId       Organization ID for permission scoping
     * @return Generated SQL
     */
    String generateSQL(String question, String contextJson, Integer orgId, String prompt);

    /**
     * Summarize query results.
     *
     * Rule 1 Compliance: This method receives ONLY a ResultProfile DTO, never raw result rows.
     * The LLM must never have access to actual business data.
     *
     * @param question    Original question
     * @param sql         Generated SQL
     * @param resultProfile Result profile containing only metadata (rowCount, columnNames, etc.)
     * @return Natural language summary
     */
    String summarizeResults(String question, String sql, ResultProfile resultProfile);

    /**
     * Repair invalid SQL based on error message
     *
     * @param sql         Original SQL
     * @param errorMessage SQL execution error
     * @param contextJson Metadata context
     * @return Repaired SQL
     */
    String repairSQL(String sql, String errorMessage, String contextJson);

    /**
     * Detect intent from question
     *
     * @param question User question
     * @return Intent type: query, report, export, etc.
     */
    String detectIntent(String question);

    /**
     * Recommend chart type based on data shape
     *
     * @param columns Column metadata
     * @param rowCount Row count
     * @return Recommended chart type: bar, line, pie, table, kpi
     */
    String recommendChartType(String columns, int rowCount);

    /**
     * Generate embedding vector for the input text.
     */
    float[] generateEmbedding(String text);

    /**
     * LLM Response data class
     */
    @Data
    class LLMResponse {
        private String content;
        private String finishReason;
        private Map<String, Object> metadata;

        public LLMResponse() {}

        public LLMResponse(String content) {
            this.content = content;
        }

        public LLMResponse(String content, String finishReason) {
            this.content = content;
            this.finishReason = finishReason;
        }
    }
}
