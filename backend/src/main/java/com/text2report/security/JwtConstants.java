package com.text2report.security;

/**
 * JWT Constants
 *
 * Fix: P1-5 - Extract blacklist prefix as constant for reuse across JwtAuthFilter and AuthService
 */
public final class JwtConstants {

    private JwtConstants() {}

    /**
     * Redis key prefix for JWT blacklist
     */
    public static final String BLACKLIST_PREFIX = "jwt:blacklist:";
}
