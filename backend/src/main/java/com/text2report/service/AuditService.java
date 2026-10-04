package com.text2report.service;

import com.text2report.domain.meta.mapper.AuditQueryLogMapper;
import com.text2report.model.dto.QueryContext;
import com.text2report.model.dto.QueryResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Audit Service - async logging of all query events
 *
 * Fix: P0-5 - All methods use explicit parameters instead of ThreadLocal TraceContext.
 *       This is required because @Async creates new threads where ThreadLocal is empty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int MAX_PROMPT_CONTEXT_LENGTH = 5000;

    private final AuditQueryLogMapper auditQueryLogMapper;

    /**
     * Log query start event
     *
     * Fix: P0-5 - Explicit parameters instead of TraceContext ThreadLocal
     * Fix: P0-2竞态消除 - Use upsert to avoid race condition
     */
    @Async("auditExecutor")
    public void logQueryStart(String requestId, Integer userId, Integer orgId, String question) {
        try {
            LocalDateTime now = LocalDateTime.now();
            auditQueryLogMapper.upsert(
                    requestId, userId, orgId, question, null, null, null, null, "pending",
                    null, null, null, null,
                    null, null, null, null, false,
                    null, null, 0, now, now
            );
            log.debug("Audit log upserted for request: {}", requestId);
        } catch (Exception e) {
            log.error("Failed to log audit start for request {}: {}", requestId, e.getMessage());
        }
    }

    @Async("auditExecutor")
    public void logQueryStart(QueryContext queryContext) {
        if (queryContext == null) {
            return;
        }
        logQueryStart(
                queryContext.getRequestId(),
                queryContext.getUserId(),
                queryContext.getOrgId(),
                queryContext.getQuestion()
        );
    }

    /**
     * Log query success event
     *
     * Fix: P0-5 - Explicit parameters instead of TraceContext ThreadLocal
     */
    @Async("auditExecutor")
    public void logQuerySuccess(String requestId, Integer userId, Integer orgId,
                                 String sql, Long executionTimeMs, Integer rowsReturned, Double confidence) {
        try {
            updateAuditLog(requestId, userId, orgId, "success", null, sql, null, null,
                    null, executionTimeMs, rowsReturned, confidence, 0, false);
        } catch (Exception e) {
            log.error("Failed to log audit success for request {}: {}", requestId, e.getMessage());
        }
    }

    /**
     * Log query blocked event
     *
     * Fix: P0-5 - Explicit parameters instead of TraceContext ThreadLocal
     */
    @Async("auditExecutor")
    public void logQueryBlocked(String requestId, Integer userId, Integer orgId, String sql, String reason) {
        try {
            updateAuditLog(requestId, userId, orgId, "blocked", null, sql, null, null,
                    reason, null, null, null, 0, false);
        } catch (Exception e) {
            log.error("Failed to log audit blocked for request {}: {}", requestId, e.getMessage());
        }
    }

    /**
     * Log query error event
     *
     * Fix: P0-5 - Explicit parameters instead of TraceContext ThreadLocal
     */
    @Async("auditExecutor")
    public void logQueryError(String requestId, Integer userId, Integer orgId, String sql, String errorMessage) {
        try {
            updateAuditLog(requestId, userId, orgId, "error", null, sql, null, null,
                    errorMessage, null, null, null, 0, false);
        } catch (Exception e) {
            log.error("Failed to log audit error for request {}: {}", requestId, e.getMessage());
        }
    }

    @Async("auditExecutor")
    public void updateAuditLog(QueryContext queryContext, QueryResultDTO queryResult) {
        if (queryContext == null || queryResult == null) {
            return;
        }

        try {
            updateAuditLog(
                    queryContext.getRequestId(),
                    queryContext.getUserId(),
                    queryContext.getOrgId(),
                    "success",
                    sanitizePromptContext(queryContext.getPrompt()),
                    queryContext.getValidatedSql(),
                    queryResult.getSummary(),
                    Optional.ofNullable(queryResult.getResultProfile()).map(com.text2report.model.dto.ResultProfile::getRecommendedChart).orElse(null),
                    null,
                    Optional.ofNullable(queryResult.getAudit()).map(QueryResultDTO.AuditInfo::getExecutionMs).orElse(0L),
                    Optional.ofNullable(queryResult.getResultProfile()).map(com.text2report.model.dto.ResultProfile::getRowCount).orElse(0),
                    Optional.ofNullable(queryContext.getConfidence()).map(java.math.BigDecimal::doubleValue).orElse(null),
                    queryContext.getRetryCount(),
                    false
            );
            updateLlmAudit(queryContext);
        } catch (Exception e) {
            log.error("Failed to update audit success log for request {}: {}", queryContext.getRequestId(), e.getMessage());
        }
    }

    @Async("auditExecutor")
    public void updateAuditLogFromCache(QueryContext queryContext, QueryResultDTO queryResult) {
        if (queryContext == null || queryResult == null) {
            return;
        }

        try {
            updateAuditLog(
                    queryContext.getRequestId(),
                    queryContext.getUserId(),
                    queryContext.getOrgId(),
                    "success",
                    sanitizePromptContext(queryContext.getPrompt()),
                    queryResult.getSql(),
                    queryResult.getSummary(),
                    Optional.ofNullable(queryResult.getResultProfile()).map(com.text2report.model.dto.ResultProfile::getRecommendedChart).orElse(null),
                    null,
                    Optional.ofNullable(queryResult.getAudit()).map(QueryResultDTO.AuditInfo::getExecutionMs).orElse(0L),
                    Optional.ofNullable(queryResult.getResultProfile()).map(com.text2report.model.dto.ResultProfile::getRowCount).orElse(0),
                    Optional.ofNullable(queryResult.getAudit()).map(QueryResultDTO.AuditInfo::getConfidence).map(java.math.BigDecimal::doubleValue).orElse(null),
                    queryContext.getRetryCount(),
                    true
            );
        } catch (Exception e) {
            log.error("Failed to update cache-hit audit log for request {}: {}", queryContext.getRequestId(), e.getMessage());
        }
    }

    @Async("auditExecutor")
    public void updateAuditLog(QueryContext queryContext, String status, String errorMessage) {
        if (queryContext == null) {
            return;
        }

        try {
            updateAuditLog(
                    queryContext.getRequestId(),
                    queryContext.getUserId(),
                    queryContext.getOrgId(),
                    status,
                    sanitizePromptContext(queryContext.getPrompt()),
                    queryContext.getValidatedSql() != null ? queryContext.getValidatedSql() : queryContext.getGeneratedSql(),
                    null,
                    null,
                    errorMessage,
                    null,
                    null,
                    Optional.ofNullable(queryContext.getConfidence()).map(java.math.BigDecimal::doubleValue).orElse(null),
                    queryContext.getRetryCount(),
                    false
            );
            updateLlmAudit(queryContext);
        } catch (Exception e) {
            log.error("Failed to update audit failure log for request {}: {}", queryContext.getRequestId(), e.getMessage());
        }
    }

    /**
     * Record user feedback
     * Fix: P0-2竞态消除 - Changed to upsert pattern
     */
    @Async("auditExecutor")
    public void logFeedback(String requestId, String feedback, String comment) {
        try {
            LocalDateTime now = LocalDateTime.now();
            auditQueryLogMapper.upsert(
                    requestId, null, null, null, null, null, null, null, null,
                    null, null, null, null,
                    feedback, null, comment, now, null,
                    null, null, 0, now, now
            );
            log.debug("Feedback upserted for request: {}", requestId);
        } catch (Exception e) {
            log.error("Failed to log feedback for request {}: {}", requestId, e.getMessage());
        }
    }

    private void updateAuditLog(String requestId, Integer userId, Integer orgId,
                                 String status, String promptContext, String sql, String summary, String recommendedChart,
                                 String errorMessage, Long executionTimeMs, Integer rowsReturned,
                                 Double confidence, Integer retryCount, Boolean cacheHit) {
        // Fix: P0-2竞态消除 - Use upsert instead of select + update to avoid race condition
        LocalDateTime now = LocalDateTime.now();
        auditQueryLogMapper.upsert(
                requestId, userId, orgId, null, promptContext, sql, summary, recommendedChart, status,
                errorMessage, executionTimeMs, rowsReturned, confidence,
                null, null, null, null, cacheHit,
                null, null, retryCount, null, now
        );
        log.debug("Audit log upserted for request: {}, status: {}", requestId, status);
    }

    String sanitizePromptContext(String promptContext) {
        if (promptContext == null || promptContext.isBlank()) {
            return null;
        }

        String sanitized = promptContext
                .replaceAll("(?i)(api[-_ ]?key\\s*[:=]\\s*)([^\\s,;]+)", "$1***")
                .replaceAll("\\bsk-[A-Za-z0-9_-]+\\b", "***")
                .replaceAll("\\bdsk-[A-Za-z0-9_-]+\\b", "***");

        if (sanitized.length() > MAX_PROMPT_CONTEXT_LENGTH) {
            return sanitized.substring(0, MAX_PROMPT_CONTEXT_LENGTH) + "...(truncated)";
        }
        return sanitized;
    }

    private void updateLlmAudit(QueryContext queryContext) {
        if (queryContext.getLlmProviderId() == null) {
            return;
        }
        auditQueryLogMapper.updateLlmAudit(
                queryContext.getRequestId(),
                queryContext.getLlmProviderId(),
                queryContext.getLlmProviderKey(),
                queryContext.getLlmVendorType(),
                queryContext.getLlmModelCode(),
                queryContext.getLlmRoutingVersion()
        );
    }
}
