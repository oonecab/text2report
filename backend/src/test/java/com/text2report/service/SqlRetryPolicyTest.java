package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.model.dto.QueryContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlRetryPolicyTest {

    private final SqlRetryPolicy retryPolicy = new SqlRetryPolicy();

    @Test
    @DisplayName("Should retry metadata and SQL shape errors before retry budget is exhausted")
    void shouldRetryMetadataAndSqlShapeErrorsBeforeRetryBudgetIsExhausted() {
        QueryContext context = QueryContext.builder()
                .retryCount(0)
                .maxRetries(2)
                .build();

        assertTrue(retryPolicy.canRetry(
                new BusinessException(ErrorCode.METADATA_VALIDATION_FAILED, "不存在的字段: gross_margin"),
                context
        ));
        assertTrue(retryPolicy.canRetry(
                new BusinessException(ErrorCode.DATASOURCE_ERROR, "syntax error"),
                context
        ));
    }

    @Test
    @DisplayName("Should not retry permission or policy violations")
    void shouldNotRetryPermissionOrPolicyViolations() {
        QueryContext context = QueryContext.builder()
                .retryCount(0)
                .maxRetries(2)
                .build();

        assertFalse(retryPolicy.canRetry(
                new BusinessException(ErrorCode.SQL_ORG_SCOPE_VIOLATION, "SQL 包含未授权组织范围"),
                context
        ));
        assertFalse(retryPolicy.canRetry(
                new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "DELETE not allowed"),
                context
        ));
    }

    @Test
    @DisplayName("Should stop retrying when retry budget is exhausted")
    void shouldStopRetryingWhenRetryBudgetIsExhausted() {
        QueryContext context = QueryContext.builder()
                .retryCount(2)
                .maxRetries(2)
                .build();

        assertFalse(retryPolicy.canRetry(
                new BusinessException(ErrorCode.DATASOURCE_ERROR, "column does not exist"),
                context
        ));
    }
}
