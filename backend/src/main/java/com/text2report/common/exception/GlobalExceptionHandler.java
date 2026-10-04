package com.text2report.common.exception;

import com.text2report.common.context.TraceContext;
import com.text2report.common.result.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Global exception handler for unified error responses
 *
 * Fix: P4-4 - Added HttpMessageNotReadableException handling
 * Fix: P4-4 - BusinessException now returns proper HTTP status codes based on ErrorCode
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResult<?>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("Business exception [requestId={}, code={}, path={}]: {}",
                TraceContext.getRequestId(), e.getCode(), request.getRequestURI(), e.getMessage());

        ApiResult<?> result = ApiResult.error(e.getErrorCode(), e.getMessage())
                .withRequestId(TraceContext.getRequestId());

        return ResponseEntity.status(mapHttpStatus(e.getErrorCode())).body(result);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<?> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        String requestId = TraceContext.getRequestId();
        log.warn("请求体解析失败 [requestId={}, path={}]: {}", requestId, request.getRequestURI(), e.getMessage());
        return ApiResult.error(ErrorCode.VALIDATION_ERROR, "请求体格式错误，请检查 JSON 格式")
                .withRequestId(requestId);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<?> handleValidationException(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.warn("Validation exception at {}: {}", request.getRequestURI(), message);
        return ApiResult.error(ErrorCode.VALIDATION_ERROR, message).withRequestId(TraceContext.getRequestId());
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<?> handleBindException(BindException e, HttpServletRequest request) {
        String message = e.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Bind failed");
        log.warn("Bind exception at {}: {}", request.getRequestURI(), message);
        return ApiResult.error(ErrorCode.VALIDATION_ERROR, message).withRequestId(TraceContext.getRequestId());
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResult<?> handleAuthenticationException(AuthenticationException e, HttpServletRequest request) {
        log.warn("Authentication exception at {}: {}", request.getRequestURI(), e.getMessage());
        return ApiResult.error(ErrorCode.UNAUTHORIZED).withRequestId(TraceContext.getRequestId());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResult<?> handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request) {
        log.warn("Access denied at {}: {}", request.getRequestURI(), e.getMessage());
        return ApiResult.error(ErrorCode.FORBIDDEN).withRequestId(TraceContext.getRequestId());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<?> handleGenericException(Exception e, HttpServletRequest request) {
        // Log full exception but don't expose internal details
        log.error("Unexpected exception at {}: {}", request.getRequestURI(), e.getMessage(), e);
        return ApiResult.error(ErrorCode.INTERNAL_ERROR, "系统内部异常，请稍后重试")
                .withRequestId(TraceContext.getRequestId());
    }

    private HttpStatus mapHttpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, SQL_ORG_SCOPE_VIOLATION -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case VALIDATION_ERROR, PARAM_INVALID, SQL_PARSE_ERROR, SQL_GUARD_BLOCKED, METADATA_VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case DATASOURCE_ERROR, METADATA_ERROR, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case EXTERNAL_SERVICE_ERROR, LLM_TIMEOUT, LLM_PARSE_FAILED, LLM_ERROR -> HttpStatus.BAD_GATEWAY;
        };
    }
}
