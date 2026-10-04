package com.text2report.common.exception;

import com.text2report.common.result.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest("GET", "/probe");
    }

    @Test
    @DisplayName("SQL Guard 拦截映射为 400")
    void shouldMapSqlGuardBlocked() {
        ResponseEntity<ApiResult<?>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "blocked"),
                request
        );
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(ErrorCode.SQL_GUARD_BLOCKED.getCode(), response.getBody().getCode());
    }

    @Test
    @DisplayName("SQL 解析失败映射为 400")
    void shouldMapSqlParseError() {
        ResponseEntity<ApiResult<?>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.SQL_PARSE_ERROR, "parse"),
                request
        );
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(ErrorCode.SQL_PARSE_ERROR.getCode(), response.getBody().getCode());
    }

    @Test
    @DisplayName("未授权映射为 401")
    void shouldMapUnauthorized() {
        ResponseEntity<ApiResult<?>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.UNAUTHORIZED, "unauthorized"),
                request
        );
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), response.getBody().getCode());
    }

    @Test
    @DisplayName("无权限映射为 403")
    void shouldMapForbidden() {
        ResponseEntity<ApiResult<?>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.FORBIDDEN, "forbidden"),
                request
        );
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(ErrorCode.FORBIDDEN.getCode(), response.getBody().getCode());
    }

    @Test
    @DisplayName("参数错误映射为 400")
    void shouldMapValidationError() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new ProbeBody(), "probeBody");
        bindingResult.rejectValue("name", "NotBlank", "must not be blank");

        Method method = ProbeController.class.getDeclaredMethod("submit", ProbeBody.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ApiResult<?> result = handler.handleValidationException(exception, request);
        assertEquals(ErrorCode.VALIDATION_ERROR.getCode(), result.getCode());
    }

    @Test
    @DisplayName("BindException 映射为 400")
    void shouldMapBindException() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new ProbeBody(), "probeBody");
        bindingResult.rejectValue("name", "NotBlank", "must not be blank");
        BindException exception = new BindException(bindingResult);

        ApiResult<?> result = handler.handleBindException(exception, request);
        assertEquals(ErrorCode.VALIDATION_ERROR.getCode(), result.getCode());
    }

    static class ProbeController {
        void submit(ProbeBody body) {
        }
    }

    static class ProbeBody {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
