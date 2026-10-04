package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.model.dto.QueryContext;
import com.text2report.model.dto.QueryPlan;
import com.text2report.model.dto.QueryResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Coordinates the end-to-end query lifecycle.
 *
 * Business strategy is delegated to focused workflow services so this class can
 * remain a small state machine as datasources, LLM providers and export flows grow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorService {

    private final QueryPlanningService queryPlanningService;
    private final SqlGenerationWorkflow sqlGenerationWorkflow;
    private final QueryExecutionWorkflow queryExecutionWorkflow;
    private final QueryResultAssembler queryResultAssembler;
    private final QueryCacheService queryCacheService;
    private final AuditService auditService;
    private final SqlRetryPolicy retryPolicy;
    private LlmProviderConfigService llmProviderConfigService;

    @Autowired(required = false)
    public void setLlmProviderConfigService(LlmProviderConfigService llmProviderConfigService) {
        this.llmProviderConfigService = llmProviderConfigService;
    }

    @Value("${app.query.max-retries:2}")
    private int maxRetries;

    public QueryResultDTO query(String question, Integer orgId) {
        QueryContext queryContext = queryPlanningService.buildContext(question, orgId, maxRetries);
        auditService.logQueryStart(queryContext);
        try {
            String modelCacheSignature = llmProviderConfigService != null
                    ? llmProviderConfigService.resolveCacheSignature()
                    : null;

            QueryResultDTO cachedResult = modelCacheSignature != null
                    ? queryCacheService.getCachedResult(question, queryContext.getOrgId(), modelCacheSignature).orElse(null)
                    : queryCacheService.getCachedResult(question, queryContext.getOrgId()).orElse(null);
            if (cachedResult != null) {
                cachedResult.setRequestId(queryContext.getRequestId());
                auditService.updateAuditLogFromCache(queryContext, cachedResult);
                return cachedResult;
            }

            QueryPlan queryPlan = queryPlanningService.plan(queryContext);
            queryContext.setValidatedSql(sqlGenerationWorkflow.generateValidatedSql(queryContext, queryPlan));
            QueryExecutorService.ExecutionResult executionResult = queryExecutionWorkflow.execute(queryContext, queryPlan);
            QueryResultDTO queryResult = queryResultAssembler.assemble(queryContext, queryPlan, executionResult);

            auditService.updateAuditLog(queryContext, queryResult);
            if (modelCacheSignature != null) {
                queryCacheService.cacheResult(question, queryContext.getOrgId(), modelCacheSignature, queryResult);
            } else {
                queryCacheService.cacheResult(question, queryContext.getOrgId(), queryResult);
            }
            return queryResult;
        } catch (BusinessException e) {
            auditService.updateAuditLog(queryContext, resolveAuditStatus(e), e.getMessage());
            throw sanitizeException(e);
        } catch (Exception e) {
            log.error("Unexpected orchestration failure for request {}", queryContext.getRequestId(), e);
            auditService.updateAuditLog(queryContext, "error", "查询执行失败");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "查询执行失败");
        }
    }

    private String resolveAuditStatus(BusinessException exception) {
        if (exception.getCode() == ErrorCode.SQL_GUARD_BLOCKED.getCode()
                || exception.getCode() == ErrorCode.SQL_ORG_SCOPE_VIOLATION.getCode()) {
            return "blocked";
        }
        return "error";
    }

    private BusinessException sanitizeException(BusinessException exception) {
        if ((exception.getCode() == ErrorCode.SQL_GUARD_BLOCKED.getCode() && !retryPolicy.isRetryableMessage(exception.getMessage()))
                || exception.getCode() == ErrorCode.SQL_ORG_SCOPE_VIOLATION.getCode()
                || exception.getCode() == ErrorCode.PARAM_INVALID.getCode()
                || exception.getCode() == ErrorCode.VALIDATION_ERROR.getCode()
                || exception.getCode() == ErrorCode.METADATA_ERROR.getCode()
                || exception.getCode() == ErrorCode.DATASOURCE_ERROR.getCode()
                || exception.getCode() == ErrorCode.EXTERNAL_SERVICE_ERROR.getCode()
                || exception.getCode() == ErrorCode.LLM_ERROR.getCode()
                || exception.getCode() == ErrorCode.LLM_PARSE_FAILED.getCode()
                || exception.getCode() == ErrorCode.LLM_TIMEOUT.getCode()) {
            return exception;
        }

        if (exception.getMessage() != null && exception.getMessage().contains("超时")) {
            return new BusinessException(ErrorCode.DATASOURCE_ERROR, "查询执行超时");
        }
        return new BusinessException(ErrorCode.INTERNAL_ERROR, "查询执行失败");
    }
}
