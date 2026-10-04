# M4-Hotfix — 安全与质量修复 (2026-04-10)

> 修复 Codex 代码审查发现的 6 个 P0 安全问题和 7 个 P1 问题

## 修复日期
2026-04-10

## P0 修复（安全性 - 必须立即修复）

### P0-1: LLMService 规则隔离违规
**问题**: `LLMService.summarizeResults()` 直接接收原始查询结果 `String results`，违反 Rule 1（LLM 只能看到 metadata，不能看到 business data）。

**修复**:
- 新增 `ResultProfile` DTO，包含 rowCount、columnNames、recommendedChart、hasData
- 修改 `LLMService.summarizeResults(String question, String sql, ResultProfile resultProfile)`
- 更新 `OpenAILLMService` 和 `LocalLLMService` 实现
- 同步更新 `OrchestratorService` 使用 ResultProfile

### P0-2: SqlGuardService AST 重写
**问题**: 原实现使用 `String.contains()` 做 SQL 安全检查，无法防御 AST 注入攻击。

**修复**:
- 使用 JSqlParser 4.9 AST Visitor 模式完全重写
- `SqlValidationVisitor` 内部类实现 `SelectVisitor` 接口
- 检测 blocked schemas: `pg_catalog`, `information_schema`, `sys`, `master`, `msdb`, `tempdb`
- 检测 blocked functions: `xp_cmdshell`, `sp_executesql`, `xp_regread` 等
- 检测 `SELECT *` via `AllColumns` AST node
- 支持 CTE (WithItem)、UNION (SetOperationList)、子查询 (ParenthesedSelect)
- `injectLimit()` 使用 `Limit` 类注入 TOP/LIMIT
- 新增 `SqlGuardProperties` 配置类（`blockSelectStar`、`defaultLimit`、`queryTimeoutSeconds`）

### P0-3: JWT Blacklist 立即失效
**问题**: 原实现 logout 后 token 在 TTL 到期前仍然有效。

**修复**:
- `JwtAuthFilter` 在验证 token 前先检查 Redis blacklist
- `AuthService.logout()` 将 token 加入 blacklist，TTL = token 剩余有效期
- 新增 `JwtConstants.BLACKLIST_PREFIX` 常量类
- `JwtUtil` 改用 `jwt.expiration-seconds` 配置（统一时间单位）

### P0-4: TraceContext RequestId 覆盖问题
**问题**: `JwtAuthFilter` 验证通过后创建新的 `TraceInfo` 覆盖了 `TraceFilter` 设置的 requestId。

**修复**:
- `TraceContext` 新增 `setUserId()`、`setOrgId()`、`setRole()` 方法
- 仅更新 TraceInfo 的特定字段，不破坏 requestId

### P0-5: @EnableAsync 未激活
**问题**: `AuditService` 使用 `@Async` 但 `AsyncConfig` 缺少 `@EnableAsync`。

**修复**:
- 在 `AsyncConfig` 类上添加 `@EnableAsync` 注解

## P1 修复（质量问题）

### P1-1: Exception 敏感信息泄露
**问题**: 多处 `catch (Exception e)` 后直接暴露 `e.getMessage()`。

**修复**:
- `QueryExecutorService`: 内部错误返回 "查询执行失败，请联系管理员"
- `HealthController`: `/details` 端点的异常消息不暴露
- `OrchestratorService`: 内部错误返回 "查询执行失败，请联系管理员"

### P1-2: HealthController Bean 冲突
**问题**: `@Qualifier` 注解缺失导致 `NoUniqueBeanDefinitionException`。

**修复**:
- `HealthController` 的 `@Autowired` 构造器参数添加 `@Qualifier("businessDataSource")` 和 `@Qualifier("metaDataSource")`

### P1-3: LLM Bean 冲突
**问题**: `OpenAILLMService` 和 `LocalLLMService` 同时注册导致 `NoUniqueBeanDefinitionException`。

**修复**:
- `OpenAILLMService` 添加 `@ConditionalOnProperty(name = "llm.provider", havingValue = "openai", matchIfMissing = true)`
- `LocalLLMService` 添加 `@ConditionalOnProperty(name = "llm.provider", havingValue = "local")`

### P1-4: SecurityConfig CORS 与角色授权
**问题**: CORS preflight 未正确豁免，角色授权不完整。

**修复**:
- CORS preflight: `.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()`
- 角色授权: `/api/v1/health/details`、`/api/v1/metadata/**`、`/api/v1/orgs/**` 需要 `hasRole("admin")`

### P1-5: JWT 配置 key 不一致
**问题**: `JwtUtil` 使用 `jwt.secret` 但配置使用 `jwt.expiration-ms`。

**修复**:
- `JwtUtil` 统一使用 `jwt.expiration-seconds`（`@Value("${jwt.expiration-seconds:86400}")`）
- `AuthService` 使用 `jwtUtil.getExpirationSeconds()` 获取 TTL

### P1-6: 凭证文件清理
**问题**: `application-local.yml` 包含真实凭证被提交到 git。

**修复**:
- `.gitignore` 改为通用模式：`*-local.yml`、`*-prod.yml`、`env-*.yml`
- 创建 `application-local.yml.template` 模板文件，占位符：`<YOUR_DB_HOST>`、`<YOUR_DB_USERNAME>` 等

### P1-7: ErrorCode 格式统一
**问题**: ErrorCode 格式与 CLAUDE.md 规范不一致。

**修复**:
- 统一格式：`ERROR_CODE_NAME(Code, "Message")`
- 4001-4005 为业务错误，5001 为内部错误

## 验证结果

### 编译验证
```
mvn compile -q
✅ 编译成功
```

### 测试验证
```
mvn test -q
✅ 33 tests passed (原 27 + 新增 6)
```

### 新增测试用例
- `testWithCteSelectIsLimitInjected()` - CTE LIMIT 注入
- `testUnionSelectIsLimitInjected()` - UNION LIMIT 注入
- `testSystemSchemaAccessBlocked()` - 系统 schema 阻止
- `testSelectStarBlocked()` - SELECT * 阻止
- `testNestedSubqueryWithDelete()` - DELETE 子查询阻止
- `testMixedCaseKeywordBlocked()` - 大小写不敏感阻止

## 后续待办
- M4-Hotfix 验证完成，可继续 M5 开发
