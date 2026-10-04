package com.text2report.common.context;

import lombok.Data;

/**
 * Thread-local trace context for request tracking
 *
 * Fix: P0-4 - Added setUserId, setOrgId, setRole for incremental updates
 */
public class TraceContext {
    private static final ThreadLocal<TraceInfo> CONTEXT = new ThreadLocal<>();

    public static void set(TraceInfo info) {
        CONTEXT.set(info);
    }

    public static TraceInfo get() {
        return CONTEXT.get();
    }

    public static String getRequestId() {
        TraceInfo info = CONTEXT.get();
        return info != null ? info.getRequestId() : null;
    }

    public static Integer getUserId() {
        TraceInfo info = CONTEXT.get();
        return info != null ? info.getUserId() : null;
    }

    public static Integer getOrgId() {
        TraceInfo info = CONTEXT.get();
        return info != null ? info.getOrgId() : null;
    }

    public static String getRole() {
        TraceInfo info = CONTEXT.get();
        return info != null ? info.getRole() : null;
    }

    /**
     * Set user ID without overwriting requestId
     * Fix: P0-4
     */
    public static void setUserId(Integer userId) {
        TraceInfo info = CONTEXT.get();
        if (info != null) {
            info.setUserId(userId);
        }
    }

    /**
     * Set org ID without overwriting requestId
     * Fix: P0-4
     */
    public static void setOrgId(Integer orgId) {
        TraceInfo info = CONTEXT.get();
        if (info != null) {
            info.setOrgId(orgId);
        }
    }

    /**
     * Set role without overwriting requestId
     * Fix: P0-4
     */
    public static void setRole(String role) {
        TraceInfo info = CONTEXT.get();
        if (info != null) {
            info.setRole(role);
        }
    }

    public static void clear() {
        CONTEXT.remove();
    }

    @Data
    public static class TraceInfo {
        private String requestId;
        private Integer userId;
        private Integer orgId;
        private String role;

        public TraceInfo() {}

        public TraceInfo(String requestId) {
            this.requestId = requestId;
        }
    }
}
