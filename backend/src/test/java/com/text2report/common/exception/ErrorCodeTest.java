package com.text2report.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorCodeTest {

    @Test
    @DisplayName("每个 ErrorCode 都应具备唯一业务码")
    void shouldUseUniqueBusinessCodes() {
        Set<Integer> distinctCodes = Arrays.stream(ErrorCode.values())
                .map(ErrorCode::getCode)
                .collect(Collectors.toSet());

        assertEquals(ErrorCode.values().length, distinctCodes.size());
    }
}
