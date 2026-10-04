package com.text2report.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class AuthServiceSecurityTest {

    @Test
    @DisplayName("AuthService 不应再通过反射或手写 Jwts.builder 签发 token")
    void authServiceShouldNotUseReflectionForJwt() throws Exception {
        Path source = Path.of("src/main/java/com/text2report/service/AuthService.java");
        String content = Files.readString(source);

        assertFalse(content.contains("getDeclaredMethod"));
        assertFalse(content.contains("getDeclaredField"));
        assertFalse(content.contains("setAccessible"));
        assertFalse(content.contains("Jwts.builder()"));
        assertFalse(content.contains("readSecretKey"));
        assertFalse(content.contains("readExpirationMs"));
    }
}
