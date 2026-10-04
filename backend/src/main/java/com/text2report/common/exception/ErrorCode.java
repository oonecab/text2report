package com.text2report.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Error codes enum
 *
 * Fix: P1-7 - Unified error codes to CLAUDE.md format (4001-4005, 5001)
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    VALIDATION_ERROR(4000, "请求参数错误"),
    PARAM_INVALID(4006, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或Token失效"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    RATE_LIMITED(429, "请求频率超限"),
    SQL_PARSE_ERROR(4001, "SQL解析失败"),
    SQL_GUARD_BLOCKED(4002, "SQL被安全策略拦截"),
    SQL_ORG_SCOPE_VIOLATION(4003, "SQL违反组织权限范围"),
    METADATA_VALIDATION_FAILED(4007, "SQL元数据校验失败"),
    DATASOURCE_ERROR(5002, "数据源访问异常"),
    METADATA_ERROR(5003, "元数据处理异常"),
    EXTERNAL_SERVICE_ERROR(5004, "外部服务调用异常"),
    LLM_TIMEOUT(5005, "外部服务调用超时"),
    LLM_PARSE_FAILED(5006, "外部服务响应解析失败"),
    LLM_ERROR(5007, "外部服务调用异常"),
    INTERNAL_ERROR(5001, "内部服务器错误");

    private final int code;
    private final String message;

    public String getMessage() {
        return message;
    }
}
