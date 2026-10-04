package com.text2report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@Component
@ConfigurationProperties(prefix = "app.security.auth-cookie")
public class AuthCookieProperties {

    private static final String LOCAL_FALLBACK_COOKIE_NAME = "t2r_access";

    private String accessCookieName = "__Host-t2r_access";

    private boolean secure = true;

    private String sameSite = "Lax";

    private String path = "/";

    /**
     * Optional. Must stay empty when using __Host- cookies.
     */
    private String domain;

    public String getResolvedAccessCookieName() {
        if (!secure && StringUtils.hasText(accessCookieName)
                && (accessCookieName.startsWith("__Host-") || accessCookieName.startsWith("__Secure-"))) {
            return LOCAL_FALLBACK_COOKIE_NAME;
        }
        return accessCookieName;
    }
}
