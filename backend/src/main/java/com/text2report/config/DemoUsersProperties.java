package com.text2report.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import static org.springframework.util.StringUtils.hasText;

/**
 * Demo users configuration properties.
 * Loads user credentials from application.yml environment variables.
 * BCrypt hashed passwords must be provided via application-local.yml (not committed to Git).
 */
@Data
@Component
@ConfigurationProperties(prefix = "demo.users")
public class DemoUsersProperties {

    private UserDef admin = new UserDef();
    private UserDef demo = new UserDef();
    private UserDef user = new UserDef();

    @PostConstruct
    public void validate() {
        if (!hasText(admin.getPasswordHash()) ||
            !hasText(demo.getPasswordHash()) ||
            !hasText(user.getPasswordHash())) {
            throw new IllegalStateException(
                "[SECURITY] demo.users.*.password-hash 未配置，服务拒绝启动。" +
                "请在 application-local.yml 中配置 BCrypt 哈希密码。"
            );
        }
    }

    public UserDef findByUsername(String username) {
        if (username == null) return null;
        if (username.equals(admin.getUsername())) return admin;
        if (username.equals(demo.getUsername())) return demo;
        if (username.equals(user.getUsername())) return user;
        return null;
    }

    @Data
    public static class UserDef {
        private String username;
        private String passwordHash;  // BCrypt hash, NOT plaintext
        private String role;           // ADMIN or USER
        private Integer orgId;
    }
}
