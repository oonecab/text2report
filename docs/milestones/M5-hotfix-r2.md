# M5-Hotfix-R2 — Codex 第二轮审查缺陷修复

> 修复日期：2026-04-11

## 修复背景
基于 Codex 第二轮静态审查，修复剩余的 P0/P1/P2 问题。

## Task 1 (P0) — AuthService 硬编码密码和角色
- [x] 创建 `DemoUsersProperties.java` 从 `application.yml` 加载用户凭证
- [x] `AuthService.login()` 使用 `demoUsersConfig.findByUsername()` + `passwordEncoder.matches()` 验证 BCrypt 哈希
- [x] 角色统一转为大写 (`ADMIN`/`USER`)
- [x] `application.yml` 添加 `demo.users` 配置节（无明文默认）

## Task 2 (P1) — JWT 密钥熵值增强验证
- [x] `JwtUtil.validateSecret()` 增加唯一字符数 ≥ 8 检查
- [x] 增加顺序字符检测（`0123456789`, `abcdefghijklmnopqrstuvwxyz` 及逆序，4字符以上）
- [x] 增加重复字符检测（4个及以上相同连续字符）
- [x] 测试 `application.yml` 密钥 `test-secret-key-for-unit-tests-must-be-at-least-32-chars` 通过验证

## Task 3 (P1) — 角色名称大小写一致性
- [x] `AuthService` 登录时将角色规范化为大写
- [x] `JwtAuthFilter` 使用 `SimpleGrantedAuthority("ROLE_" + role)` 自动加前缀
- [x] `SecurityConfig` 所有 `hasRole('ADMIN')` 均为大写
- [x] 所有 `@PreAuthorize("hasRole('ADMIN')")` 均为大写

## Task 4 (P1) — OrgTreeMapper ILIKE 查询优化
- [x] `is_active` → `active` 列名修正（schema 确认）
- [x] 两个 CTE 均使用 `active = true` 过滤

## Task 5 (P2) — CompletionException 解包处理
- [x] `OpenAILLMService` 新增 `callLLMSafely()` 私有方法
- [x] 捕获 `join()` 抛出的 `CompletionException`，解包为 `BusinessException` 后再抛出
- [x] `generateSQL`、`repairSQL`、`summarizeResults` 均使用 `callLLMSafely()`

## Task 6 (P1) — DDLConfirmRequest DTO 提取
- [x] 创建 `model/dto/DDLParseRequest.java`（`@NotBlank` 验证）
- [x] 创建 `model/dto/DDLConfirmRequest.java`（`@NotEmpty` 验证，含嵌套 `ParsedTable`/`ParsedColumn`）
- [x] `MetaDataSourceConfig` 新增 `metaTransactionManager` Bean
- [x] `DDLParserService.confirmAndSave` 绑定 `@Transactional(transactionManager = "metaTransactionManager")`
- [x] `DDLParserService` 内部 `DDLParseResult.ParsedTable`/`ParsedColumn` 重用 `DDLConfirmRequest` 中的定义

## Task 7 (P2) — Mockito JDK21 兼容性
- [x] Spring Boot 3.2 默认引入 Mockito 5.x，兼容 JDK21
- [x] `spring-boot-starter-test` 提供完整支持

## Task 8 (P2) — DDLParserService 单元测试新增
- [x] `testDecimalTypePreservedAfterSplit` — 验证 `DECIMAL(10,2)`、`NUMERIC(18,4)` 括号内逗号不误拆
- [x] `testSpExtendedPropertyMultipleColumns` — 验证多条 `sp_addextendedproperty` 正确解析多列
- [x] `testNestedParenthesisDDLParsed` — 验证嵌套括号类型正确拆分

## Task 9 (P1) — OrgService.updateOrg 循环引用检查优化
- [x] 仅当 `parentId` 实际发生变化时才执行递归 CTE 查询
- [x] `resolveSubtreeIds()` 调用次数减少（父节点不变时跳过）

## 测试结果
```
[INFO] Tests run: 99, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 架构红线合规（修复后）
| 红线 | 状态 |
|------|------|
| Rule 1: LLM只看元数据 | ✅ |
| Rule 2: SqlGuard不可绕过 | ✅ |
| Rule 3: 业务库只读 | ✅ |
| Rule 4: 凭据不硬编码 | ✅ |

## M6-READY 判定：✅
