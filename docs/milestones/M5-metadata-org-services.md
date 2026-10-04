# M5 — 组织 + 元数据服务

> 完成日期：2026-04-11

## 目标

实现组织树管理和元数据服务的完整业务逻辑，为 M6（LLM + SQL Guard 服务编排）提供基础设施。

## 完成清单

### 1. PromptBuilder 提示词模板工具
- [x] `PromptBuilder.java` — 模板加载与填充组件
  - `loadTemplate(String)` — 支持文件系统 + classpath: 前缀
  - `fill(String, Map<String,String>)` — {key} 占位符替换
  - `buildSqlGenerationPrompt(...)` — SQL 生成提示词
  - `buildSqlRepairPrompt(...)` — SQL 修复提示词
  - `buildResultSummaryPrompt(...)` — 结果摘要提示词
- [x] 3个提示词模板文件
  - `/prompts/sql_generation_v1.txt`
  - `/prompts/sql_repair_v1.txt`
  - `/prompts/result_summary_v1.txt`

### 2. MetadataService 完整实现
- [x] `MetadataContext.java` — DTO（tables/columns/glossary/joinEdges/examples）
- [x] `MetadataService.java` — 完整业务逻辑
  - `retrieveForPrompt(String, Integer)` → MetadataContext
  - `listActiveDatasources()` / `listTablesByDatasource()` / `listColumnsByTable()`
  - `searchGlossary(String)` / `getJoinEdges(List<String>)` / `getExamples(String, int)`
  - `buildMetadataJson(MetadataContext)` — JSON 结构构建
  - 向后兼容包装方法（getMetadataContext/getTablesMetadata/getColumnsMetadata/getGlossary/getSqlExamples）

### 3. OrgService 组织树服务
- [x] `OrgTreeNode.java` — DTO（id/parentId/name/code/level/isActive/children）
- [x] `OrgService.java` — 完整业务逻辑
  - `getFullOrgTree()` → List<OrgTreeNode>（嵌套树结构）
  - `resolveSubtreeIds(Integer)` — PostgreSQL 递归 CTE + Redis 缓存（TTL 3600s）
  - `resolveOrgPath(Integer)` — 祖先路径解析
  - `createOrg()` / `updateOrg()` / `deactivateOrg()`（含缓存清理）
  - 向后兼容包装方法（getOrgTree/getOrgAndChildrenIds/resolveOrgContext/hasAccessToOrg）
- [x] `OrgTreeMapper.java` 新增方法
  - `selectSubtreeIds(Integer)` — 递归 CTE 查询
  - `selectAncestorPath(Integer)` — 祖先路径查询
- [x] `OrgTreeMapper.xml` — PostgreSQL 递归 CTE 实现

### 4. DDLParserService DDL 解析服务
- [x] `DDLParserService.java` — 完整业务逻辑
  - 三种注释格式支持：
    1. 行注释 `-- 注释`
    2. `sp_addextendedproperty` 格式（SQL Server 兼容）
    3. `COMMENT ON COLUMN` 语法
  - `parseDDL(String, String)` → DDLParseResult
  - `confirmAndSave(DDLConfirmRequest, String)` — 事务性保存
  - `commitDDL(String)` — 向后兼容单步方法
  - 嵌套 DTO：DDLParseResult.ParsedTable/ParsedColumn, DDLConfirmRequest, DDLCommitResult

### 5. OpenAILLMService DeepSeek API 调用
- [x] `LLMConfig.java` — 新增 threadPoolSize 字段
- [x] `AsyncConfig.java` — 新增 llmExecutor Bean（core=4, max=8, queue=50, CallerRunsPolicy）
- [x] `ErrorCode.java` — 新增 LLM_ERROR(503002)
- [x] `OpenAILLMService.java` — 完整实现
  - `@Async("llmExecutor")` 异步调用
  - `generateSQL(String, String, Integer)` — SQL 生成（使用 PromptBuilder + JSON 解析）
  - `summarizeResults(String, String, ResultProfile)` — Rule 1 合规（不传原始结果）
  - `repairSQL(String, String, String)` — 错误消息传递
  - `detectIntent(String)` — 关键词检测（extreme/trend/ranking/aggregation/comparison/query）
  - `recommendChartType(String, int)` — 基于行/列数推荐
  - `callLLMSync()` — OkHttp 调用 + `response_format: {type: "json_object"}`
  - `parseResponse(String)` — LLMResponse 提取（usage/message/model）
  - 内部类 `LLMResponse`（rawResponse/promptTokens/completionTokens/model）

### 6. Controller 端点
- [x] `MetadataController.java` 新增端点
  - `POST /ddl/parse` / `POST /ddl/confirm` — DDL 解析确认
  - `GET /tables?datasourceId=` — 数据源表列表
  - `GET /tables/{tableId}/columns` — 表字段列表
  - `GET /glossary?keyword=` — 术语查询
  - `POST/DELETE /glossary` — 术语管理（@PreAuthorize ADMIN）
- [x] `OrgController.java` 新增端点
  - `GET /tree` → List<OrgTreeNode>
  - `GET /{orgId}/subtree-ids` → List<Integer>
  - `GET /{orgId}/path` → String
  - `POST/PUT/DELETE /` — 组织管理（@PreAuthorize ADMIN）

### 7. 单元测试
- [x] `PromptBuilderTest.java` — 13 tests
  - 模板加载（3 tests）
  - 占位符填充（4 tests）
  - SQL 生成提示词（2 tests）
  - SQL 修复提示词（1 test）
  - 结果摘要提示词（1 test）
  - 向后兼容（2 tests）
- [x] `MetadataServiceTest.java` — 11 tests
  - retrieveForPrompt（3 tests）
  - listActiveDatasources（2 tests）
  - buildMetadataJson（3 tests）
  - 向后兼容包装（3 tests）
- [x] `OrgServiceTest.java` — 12 tests
  - getFullOrgTree（2 tests）
  - resolveSubtreeIds（4 tests）
  - resolveOrgPath（3 tests）
  - getOrgById（2 tests）
  - deactivateOrg（1 test）
- [x] `DDLParserServiceTest.java` — 9 tests
  - 行注释解析（2 tests）
  - COMMENT ON 语法（1 test）
  - 多表解析（1 test）
  - 错误处理（3 tests）
  - DDLParseResult DTO（2 tests）
- [x] `OpenAILLMServiceTest.java` — 13 tests
  - detectIntent（5 tests）
  - recommendChartType（4 tests）
  - LLMResponse 解析（2 tests）
  - 向后兼容（2 tests）

**总计：58 新增测试 + 35 原有测试 = 93 测试全部通过**

### 8. 配置更新
- [x] `application.yml` — 新增 `app.prompt-dir: ${PROMPT_DIR:prompts/}`
- [x] LLM 配置更新（DeepSeek DEEPSEEK_API_KEY/DEEPSEEK_BASE_URL/DEEPSEEK_MODEL）
- [x] `application-local.yml.template` — 更新 DeepSeek 配置占位符

## 测试结果

```
[INFO] Tests run: 93, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 架构合规

- Rule 1（LLM只看到metadata）— `summarizeResults` 使用 ResultProfile，不传原始数据
- Rule 2（SqlGuardService AST）— OrchestratorService 将在 M6 使用
- Rule 3（只读连接）— Business DataSource 保持 read-only
- Rule 8（审计）— AuditService 异步日志保持

## M6 待办

- [ ] OrchestratorService 端到端流程串联
- [ ] LLM + SQL Guard 服务集成
- [ ] 查询编排 + 重试策略
