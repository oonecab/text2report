package com.text2report.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.text2report.common.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * Unified API response wrapper
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResult<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("request_id")
    private String requestId;
    private int code;
    private String message;
    private T data;

    private ApiResult() {}

    public static <T> ApiResult<T> success(T data) {
        ApiResult<T> result = new ApiResult<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> ApiResult<T> success() {
        return success(null);
    }

    public static <T> ApiResult<T> error(int code, String message) {
        ApiResult<T> result = new ApiResult<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    public static <T> ApiResult<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> ApiResult<T> error(ErrorCode errorCode, String customMessage) {
        return error(errorCode.getCode(), customMessage);
    }

    public ApiResult<T> withRequestId(String requestId) {
        this.requestId = requestId;
        return this;
    }
}
