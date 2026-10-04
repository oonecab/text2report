package com.text2report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.model.dto.QueryContext;
import com.text2report.model.dto.QueryPlan;
import com.text2report.model.dto.QueryResultDTO;
import com.text2report.model.dto.ResultProfile;
import com.text2report.service.llm.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Assembles user-facing results while keeping the LLM on ResultProfile only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryResultAssembler {

    private final QueryExecutorService queryExecutorService;
    private final LLMService llmService;
    private final QueryConfidenceService queryConfidenceService;
    private final ObjectMapper objectMapper;

    public QueryResultDTO assemble(QueryContext queryContext,
                                   QueryPlan plan,
                                   QueryExecutorService.ExecutionResult executionResult) {
        List<QueryResultDTO.ColumnMeta> columns =
                queryExecutorService.enrichColumnMeta(executionResult.getColumns(), plan.getDatasourceId());

        ResultProfile resultProfile = ResultProfile.builder()
                .recommendedChart(recommendChart(columns, executionResult.getRowCount()))
                .rowCount(executionResult.getRowCount())
                .columnCount(columns.size())
                .columnNames(columns.stream().map(QueryResultDTO.ColumnMeta::getKey).toList())
                .hasData(executionResult.getRowCount() > 0)
                .build();

        String summary = llmService.summarizeResults(
                queryContext.getQuestion(),
                queryContext.getValidatedSql(),
                resultProfile
        );

        return QueryResultDTO.builder()
                .requestId(queryContext.getRequestId())
                .sql(queryContext.getValidatedSql())
                .isFavorited(false)
                .table(QueryResultDTO.TableData.builder()
                        .columns(columns)
                        .rows(executionResult.getRows())
                        .build())
                .resultProfile(resultProfile)
                .summary(summary)
                .warnings(buildWarnings(queryContext))
                .audit(QueryResultDTO.AuditInfo.builder()
                        .confidence(queryConfidenceService.resolveConfidence(queryContext))
                        .retryCount(queryContext.getRetryCount())
                        .executionMs(executionResult.getExecutionMs())
                        .build())
                .build();
    }

    private List<String> buildWarnings(QueryContext queryContext) {
        if (queryContext.getRetryCount() <= 0) {
            return List.of();
        }
        return List.of("系统已自动重试 " + queryContext.getRetryCount() + " 次并完成修复");
    }

    private String recommendChart(List<QueryResultDTO.ColumnMeta> columns, int rowCount) {
        try {
            String columnJson = objectMapper.writeValueAsString(
                    columns.stream().map(QueryResultDTO.ColumnMeta::getKey).toList()
            );
            return llmService.recommendChartType(columnJson, rowCount);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize columns for chart recommendation", e);
            return "table";
        }
    }
}
