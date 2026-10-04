package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.model.dto.QueryContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Centralizes SQL retry classification and retry accounting.
 */
@Component
public class SqlRetryPolicy {

    private static final Set<Integer> NON_RETRYABLE_CODES = Set.of(
            ErrorCode.SQL_ORG_SCOPE_VIOLATION.getCode(),
            ErrorCode.FORBIDDEN.getCode(),
            ErrorCode.PARAM_INVALID.getCode(),
            ErrorCode.VALIDATION_ERROR.getCode()
    );

    public boolean canRetry(BusinessException exception, QueryContext context) {
        if (exception == null || context == null || context.getRetryCount() >= context.getMaxRetries()) {
            return false;
        }
        if (NON_RETRYABLE_CODES.contains(exception.getCode())) {
            return false;
        }
        if (exception.getCode() == ErrorCode.SQL_GUARD_BLOCKED.getCode()
                && !isRetryableMessage(exception.getMessage())) {
            return false;
        }
        return isRetryableMessage(exception.getMessage())
                || exception.getCode() == ErrorCode.SQL_PARSE_ERROR.getCode()
                || exception.getCode() == ErrorCode.METADATA_VALIDATION_FAILED.getCode();
    }

    public boolean isRetryableMessage(String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return false;
        }
        String lower = errorMessage.toLowerCase(Locale.ROOT);
        return lower.contains("column")
                || lower.contains("table")
                || lower.contains("relation")
                || lower.contains("does not exist")
                || lower.contains("未发布或不存在的数据表")
                || lower.contains("不存在的字段")
                || lower.contains("元数据校验")
                || lower.contains("syntax error")
                || lower.contains("ambiguous");
    }

    public void recordRetry(QueryContext context, String errorMessage) {
        context.getErrors().add(errorMessage);
        context.setRetryCount(context.getRetryCount() + 1);
    }
}
