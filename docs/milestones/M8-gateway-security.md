# M8 — 网关层 + CORS + 限流 + 审计增强

> 完成日期：2026-04-12

## 交付物清单
- [x] `SecurityConfig` CORS 配置改为从 `application.yml` / 环境变量读取
- [x] `RateLimitFilter` 基于 Redis ZSET 的滑动窗口限流
- [x] `RequestLoggingFilter` 请求开始/结束日志
- [x] `AuditController` 审计日志分页、统计、详情 API
- [x] `AuditQueryService` 审计视图组装与统计汇总
- [x] `HealthController` 增强依赖组件健康检查
- [x] `TraceFilter` 支持透传 `X-Request-ID` 并在响应头回写
- [x] `SqlGuardService` 增加 SQL 长度限制
- [x] `AuditService` 增加 prompt 脱敏与长度截断
- [x] M8 测试补齐并修复 `AuditControllerTest` MVC 切片装配
- [x] `docs/STATUS.md` 更新

## 关键实现摘要
- CORS 允许源、方法、请求头与缓存时长全部通过 `app.cors.*` 配置读取，支持开发环境多域名和生产环境单域名切换。
- `RateLimitFilter` 使用 `ZADD + ZREMRANGEBYSCORE + ZCARD` 实现 60 秒滑动窗口，区分匿名、普通用户、管理员以及 `/api/v1/query` 的更严格限额；Redis 不可用时降级放行并记录 WARN。
- `RequestLoggingFilter` 统一记录请求入口/出口日志，不打印请求体；`TraceFilter` 优先接受前端传入的 `X-Request-ID`，并始终在响应中返回，便于前后端链路关联。
- 审计管理 API 已提供分页日志、统计卡片和单条详情查询，且全部受 `@PreAuthorize("hasRole('ADMIN')")` 保护。
- `AuditService` 会对 `prompt_context` 做 API Key 脱敏并在超过 5000 字符时截断；`SqlGuardService` 在 AST 校验前额外限制 SQL 最大长度为 10000 字符。
- `HealthController` 现在可检查业务库、元数据库、Redis、LLM 配置、应用版本与运行时长，便于 Demo 和预生产环境巡检。

## 测试结果
```text
mvn -q -DskipTests compile  ✅
mvn -q test                 ✅

Tests run: 156, Failures: 0, Errors: 0, Skipped: 0
```

## 架构红线合规状态

| 检查项 | 状态 | 说明 |
|------|------|------|
| CORS 配置外置 | ✅ | `SecurityConfig` 通过 `app.cors.*` 读取，不硬编码域名 |
| 限流 Key 命名规范 | ✅ | 使用 `t2r:ratelimit:{identifier}:{endpoint}` |
| Prompt 脱敏后落审计 | ✅ | `AuditService.sanitizePromptContext(...)` 会掩码 API Key 并截断 |
| 新增 admin API 权限保护 | ✅ | `AuditController` 与 `/api/v1/health/details` 均要求 `ADMIN` |
| X-Request-ID 全链路传递 | ✅ | `TraceFilter` 支持请求透传并在响应头回写 |
| Redis 降级不影响核心功能 | ✅ | 限流与缓存在 Redis 异常时均降级，不阻断主链路 |
| SQL 长度限制 | ✅ | `SqlGuardService` 在解析前阻断超长 SQL |

## 遗留问题
- `application.yml` 当前已关闭时间戳序列化，但前后端最终字段级契约仍需要在 M9 联调时再做一次逐项核对。
- 健康检查中的数据库探活目前使用 `SELECT 1` 和 Redis `PING`，足够支撑 Demo，但更细粒度的依赖指标仍可在后续接入 Actuator 指标体系。
