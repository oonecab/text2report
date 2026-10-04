# M4-Hotfix-R3 — 第三轮安全缺陷修复（最终修复轮）(2026-04-11)

> 修复 Codex 第三轮验证审查发现的 P0 BLOCKER 和 STILL-BROKEN 问题

## 修复日期
2026-04-11

## P0 BLOCKER 修复

### Task 1: SqlGuardService 最终完整化 ✅
**问题**: AST校验存在三处独立缺陷：LIMIT字符串改写、t.*绕过漏洞、SubSelect遍历不完整。

**修复**:
- 彻底移除 `replaceAll` 字符串改写，LIMIT注入改为纯AST操作 + `statement.toString()`
- 新增 `AllTableColumns` 检测（JSqlParser 4.9中`SELECT t.*`解析为`AllColumns`）
- 修复复合schema判断，改用AST `table.getDatabase()` 和 `table.getSchemaName()`
- 补充 `BinaryExpression`（AND/OR/比较运算符）和 `Parenthesis` 的递归遍历
- **新增8个测试用例**覆盖此前三轮均未覆盖的场景

### Task 2: AuditService 竞态与 Schema 约束最终修复 ✅
**问题**: upsert需要UNIQUE约束但表中缺失；`logQueryStart`仍用insert有竞态风险。

**修复**:
- `schema-meta.sql`: `audit_query_log.request_id` 添加 `UNIQUE` 约束
- `schema-meta.sql`: 补充 `retry_count` 和 `updated_at` 字段
- `logQueryStart` 改为调用 upsert，消除三方法竞态
- `AuditQueryLogMapper.xml` upsert语句验证完整（覆盖所有字段）
- `AsyncConfig` 自定义 `RejectedExecutionHandler` 记录丢弃事件

## P1/INCOMPLETE 修复

### Task 3: JwtAuthFilter 认证失败响应完整性 ✅
**问题**: 格式错误的Authorization头（如`Authorization: Basic xxx`）被当成"无token"处理。

**修复**:
- `extractToken()` 区分"无token"（null）、"格式错误token"（空字符串）和"有效token"
- 格式错误统一返回401 JSON
- `SecurityConfig` 添加 `AuthenticationEntryPoint` 和 `AccessDeniedHandler`

### Task 4: Controller 异常处理完全统一 ✅
**问题**: MetadataController(6处)和OrgController(3处)仍有重复catch块；BusinessException固定返回HTTP 200。

**修复**:
- MetadataController 6处catch(BusinessException)全部删除
- OrgController 3处catch(BusinessException)全部删除
- GlobalExceptionHandler 新增 `HttpMessageNotReadableException` 处理
- GlobalExceptionHandler BusinessException处理改为按code映射HTTP状态码（401/403/404/400/200）
- ApiResult已有requestId字段，全部handler方法已设置

### Task 5: 配置文件跨文件一致性 ✅
**问题**: 多处配置文件键名不一致。

**修复**:
- `backend/src/test/resources/application.yml`: expiration-ms → expiration-seconds
- `application.yml`: JWT环境变量名统一为 `JWT_EXPIRATION_SECONDS`
- `application-local.yml`: LLM timeout → timeout-seconds
- `application-ex.yml`: LLM timeout → timeout-seconds，OPENAI_TIMEOUT → OPENAI_TIMEOUT_SECONDS
- `application-local.yml.template`: LLM timeout → timeout-seconds

## 架构红线合规状态（修复后）

| 红线 | M4 | Hotfix | Hotfix-R2 | Hotfix-R3 |
|------|-----|--------|-----------|-----------|
| Rule 1: LLM只能看元数据 | ❌ | ✅ | ✅ | ✅ |
| Rule 2: JSqlParser AST强制校验 | ❌ | ❌ | ❌ | ✅ |
| Rule 3: 只读双重保障 | ⚠️ | ⚠️ | ✅ | ✅ |
| Rule 8: 全量异步审计 | ❌ | ❌ | ❌ | ✅ |

## 测试验证
- mvn compile：✅
- mvn test：✅（41个测试通过，新增8个SqlGuardService测试）

## 遗留待M5处理
- OrchestratorService改为QueryContext对象（M5重构）
- QueryController响应DTO对齐API契约（M5联调）
- AuthService/JwtUtil/AuditService集成测试（M5补充）
- SqlGuardProperties.queryTimeoutSeconds接入业务代码（M5）
- application-local.yml Git历史凭据清理（团队协作）

## M5-READY判定：✅ 四条红线全部合规，可以开始M5开发
