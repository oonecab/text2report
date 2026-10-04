package com.text2report.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthCookiePropertiesTest {

    @Test
    void shouldFallbackToLocalCookieNameWhenHostPrefixIsInsecure() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setAccessCookieName("__Host-t2r_access");
        properties.setSecure(false);

        assertEquals("t2r_access", properties.getResolvedAccessCookieName());
    }

    @Test
    void shouldKeepConfiguredCookieNameWhenSecure() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setAccessCookieName("__Host-t2r_access");
        properties.setSecure(true);

        assertEquals("__Host-t2r_access", properties.getResolvedAccessCookieName());
    }
}
