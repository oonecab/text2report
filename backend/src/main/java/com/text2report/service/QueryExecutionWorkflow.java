package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.model.dto.QueryContext;
import com.text2report.model.dto.QueryPlan;
import com.text2report.service.llm.LLMService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Executes validated SQL and asks the SQL workflow to repair retryable failures.
 */
@Service
@RequiredArgsConstructor
public class QueryExecutionWorkflow {

    private final QueryExecutorService queryExecutorService;
    private final LLMService llmService;
    private final SqlGenerationWorkflow sqlGenerationWorkflow;
    private final SqlRetryPolicy retryPolicy;

    public QueryExecutorService.ExecutionResult execute(QueryContext queryContext, QueryPlan plan) {
        for (int attempt = 0; attempt <= queryContext.getMaxRetries(); attempt++) {
            try {
                return queryExecutorService.execute(queryContext.getValidatedSql());
            } catch (BusinessException executionException) {
                if (!retryPolicy.canRetry(executionException, queryContext)) {
                    throw executionException;
                }

                retryPolicy.recordRetry(queryContext, executionException.getMessage());
                String repairedSql = llmService.repairSQL(
                        queryContext.getValidatedSql(),
                        executionException.getMessage(),
                        plan.getMetadataJson()
                );
                queryContext.setGeneratedSql(repairedSql);
                queryContext.setValidatedSql(sqlGenerationWorkflow.validateOrRepair(queryContext, plan, repairedSql));
            }
        }
        throw new BusinessException(com.text2report.common.exception.ErrorCode.INTERNAL_ERROR, "查询执行失败");
    }
}
