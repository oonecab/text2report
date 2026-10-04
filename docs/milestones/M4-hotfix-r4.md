# M4-Hotfix-R4 — 第四轮安全与质量清理（最终轮）(2026-04-11)

> 修复 Codex 第四轮验证审查发现的 5 个 P1/CLEANUP 问题

## 修复日期
2026-04-11

## Task 1: SqlGuardService 两处递归遍历盲区 ✅

**问题**: AST 递归遍历存在两处遗漏：
- `BinaryExpression` 递归时未处理 `ParenthesedSelect`（标量子查询如 `WHERE id = (SELECT ...)`）
- `JOIN ON` 条件表达式未验证子查询

**修复**:
- `validateExpression()` 中 `BinaryExpression` 分支新增 `ParenthesedSelect` 处理
- `validatePlainSelect()` 中 `JOIN` 循环新增 `join.getOnExpression()` 验证
- 删除残留 `convertLimitToTop()` 方法（包含 `replaceAll` 字符串改写）
- 新增 2 个测试用例覆盖盲区场景

## Task 2: AuditService 三项清理 ✅

**Task 2-A**: AsyncConfig CallerRunsPolicy ✅
- `auditExecutor` 的 `RejectedExecutionHandler` 改回 `CallerRunsPolicy()`
- 审计任务队列满时由调用线程执行，避免任务丢失

**Task 2-B**: AuditQueryLog 实体补齐字段 ✅
- 新增 `retryCount` 字段（`Integer`）
- 新增 `updatedAt` 字段（`LocalDateTime`，带 `@TableField(fill = FieldFill.INSERT_UPDATE)` 自动填充）

**Task 2-C**: schema-meta.sql 重复 UNIQUE 约束移除 ✅
- `audit_query_logs.request_id` 建表时已定义 `UNIQUE`
- 迁移脚本中移除重复的 `ALTER TABLE ... ADD CONSTRAINT uq_audit_request_id`

## Task 3: GlobalExceptionHandler 5001 → HTTP 500 ✅

**问题**: `ErrorCode.INTERNAL_ERROR(5001)` 错误映射到 HTTP 200（OK），违反 HTTP 语义

**修复**:
- 在 `handleBusinessException()` 中新增 `code == 5001` → `HttpStatus.INTERNAL_SERVER_ERROR` 分支
- 其他未匹配 code 仍返回 HTTP 200（业务错误码）

## Task 4: test/resources/application.yml LLM timeout 键名 ✅

**问题**: 测试配置使用 `timeout: 30`，与主配置 `timeout-seconds: 60` 不一致

**修复**: 键名统一为 `timeout-seconds: 30`

## Task 5: SqlGuardServiceTest 注释修正 ✅

**问题**: `testAllTableColumnsBlocked()` 注释声称 JSqlParser 将 `t.*` 解析为 `AllColumns`

**修复**: 注释修正为 "JSqlParser parses t.* as AllTableColumns"，与实际实现一致

## 测试验证
- mvn compile：✅
- mvn test：✅（41 个测试通过）

## M4-Hotfix-R4 状态：✅ 全部完成
