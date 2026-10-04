# M4-Hotfix-R2 — 安全与质量修复（第二轮） (2026-04-11)

> 修复 Codex 第二轮代码审查发现的 8 个遗留问题

## 修复日期
2026-04-11

## BLOCKER 修复

### Task 1: SqlGuardService AST校验完整化 ✅
**问题**: AST校验仍存在字符串/regex回退，未完整覆盖所有SQL节点。

**修复**:
- 完全移除字符串/regex回退，使用纯AST遍历
- 扩展 `validatePlainSelect` 覆盖: SELECT items、CTE (WithItem)、HAVING、ORDER BY
- 扩展 `validateExpression` 覆盖: EXISTS/IN/LIKE 子查询
- 修复复合schema名检测 (如 `master.sys.databases`)

### Task 2: AuditService竞态消除 ✅
**问题**: select+update 模式存在竞态条件，异步日志可能丢失。

**修复**:
- 新增 `upsert` 方法到 `AuditQueryLogMapper.xml` (PostgreSQL `ON CONFLICT DO UPDATE`)
- `updateAuditLog` 改用upsert替代select+update
- `logFeedback` 改用upsert
- `AsyncConfig` 改用 `DiscardPolicy` 避免 CallerRunsPolicy 阻塞

### Task 3: JwtAuthFilter无效token返回空200 ✅
**问题**: 无效token经过filter但未设置响应，导致前端收到空200。

**修复**:
- `validateAndSetAuthentication` 返回 false 时不再继续filter chain
- 新增 `sendUnauthorized` 方法返回标准401 JSON响应
- 黑名单检查和token校验失败均调用 `sendUnauthorized`

## P1/INCOMPLETE 修复

### Task 4: HealthController @Qualifier真正生效 ✅
**问题**: `@Qualifier` 注解在字段上但使用 `@RequiredArgsConstructor`，Lombok生成构造参数按字母排序。

**修复**:
- 移除 `@RequiredArgsConstructor`
- 改用手动构造器，显式 `@Qualifier` 注解构造参数

### Task 5: Controller异常处理统一 ✅
**问题**: `QueryController` 和 `AuthController` 有重复catch块。

**修复**:
- 移除 `QueryController.submitQuery` 中的 `catch (BusinessException)`
- 移除 `AuthController.login` 中的 `catch (BusinessException)`
- 统一由 `GlobalExceptionHandler` 处理

### Task 6: JWT配置键名统一 ✅
**问题**: `application-local.yml` 和 `application-ex.yml` 使用 `expiration-ms` 而非 `expiration-seconds`。

**修复**:
- `application-local.yml`: `expiration-ms: 86400000` → `expiration-seconds: 86400`
- `application-ex.yml`: `expiration-ms: ${JWT_EXPIRATION_MS:86400000}` → `expiration-seconds: ${JWT_EXPIRATION_SECONDS:86400}`

### Task 7: LLMConfig配置键名一致性 ✅
**问题**: `application.yml` 使用 `timeout-seconds` 但 `LLMConfig.OpenAI` 字段为 `timeout`。

**修复**:
- `LLMConfig.OpenAI.timeout` → `timeoutSeconds`
- `LLMConfig.Local.timeout` → `timeoutSeconds`
- 更新 `OpenAILLMService.buildClient()` 使用 `getTimeoutSeconds()`

### Task 8: ErrorCode体系完整统一 ✅
**问题**: `UNAUTHORIZED(401001)`、`FORBIDDEN(403001)`、`NOT_FOUND(404001)` 格式错误。

**修复**:
- `UNAUTHORIZED(401001, ...)` → `UNAUTHORIZED(401, ...)`
- `FORBIDDEN(403001, ...)` → `FORBIDDEN(403, ...)`
- `NOT_FOUND(404001, ...)` → `NOT_FOUND(404, ...)`

## 验证结果

### 编译验证
```
mvn compile -q
✅ 编译成功
```

### 测试验证
```
mvn test -q
✅ 33 tests passed
```

## 后续待办
- M4-Hotfix-R2 验证完成，可继续 M5 开发
