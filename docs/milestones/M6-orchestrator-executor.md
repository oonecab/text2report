# M6 — QueryExecutorService + OrchestratorService 端到端编排

> 完成日期：2026-04-11

## 交付物清单
- [x] `QueryContext` 查询生命周期上下文 DTO
- [x] `QueryRequest` / `QueryResultDTO` 查询接口 DTO
- [x] `QueryExecutorService` JDBC 动态 SQL 执行引擎
- [x] `QueryExecutorService.enrichColumnMeta(...)` 列元数据增强
- [x] `OrchestratorService.query(...)` 端到端编排主链路
- [x] `QueryController` 改造为真实查询入口
- [x] `AuditService` 增强：支持基于 `QueryContext` 的起始/成功/失败审计更新
- [x] `application.yml` 增加 `app.query.*` 配置
- [x] M6 单元测试补齐
- [x] `docs/STATUS.md` 更新

## 关键实现摘要
- `OrchestratorService` 已串联意图识别、组织上下文解析、元数据检索、Prompt 组装、LLM SQL 生成、`SqlGuardService` 校验、SQL 执行、结果摘要与审计更新。
- 增加最多 2 次的自动修复重试，对字段不存在、表不存在、语法错误、歧义列等可重试错误进行 LLM 修复后重跑。
- `QueryExecutorService` 仅执行已校验 SQL，使用只读业务数据源、`PreparedStatement`、查询超时和受限结果集读取。
- 结果摘要严格只向 LLM 传递 `ResultProfile`，不传递任何原始结果行。

## 测试结果
```text
mvn -q -DskipTests compile  ✅
mvn -q test                 ✅

Tests run: 121, Failures: 0, Errors: 0, Skipped: 0
```

## 架构红线合规状态

| 检查项 | 状态 | 说明 |
|------|------|------|
| Rule 1: LLM 只看元数据 | ✅ | `summarizeResults(...)` 仅接收 `ResultProfile`，未传原始 rows |
| Rule 2: SqlGuard 不可绕过 | ✅ | Orchestrator 中执行前必须先 `sqlGuardService.validate(...)` |
| Rule 3: 只读 SQL 执行 | ✅ | `QueryExecutorService` 使用 `businessDataSource`，且只执行校验后 SQL |
| Rule 4: 请求可审计 | ✅ | 查询开始、成功、失败路径均调用 `AuditService` |
| Rule 5: 结构化输出 | ✅ | `QueryResultDTO` 为结构化响应；LLM 仍按 JSON 结果解析 |
| Rule 6: LLM 接口隔离 | ✅ | Orchestrator 仅依赖 `LLMService` 接口 |
| Prompt 文件外置 | ✅ | Prompt 继续通过 `PromptBuilder` 从 `/prompts` 读取 |

## 遗留问题
- `LLMService` 当前仍由实现类内部组装最终 Prompt；M6 中已在编排层保留完整 Prompt 上下文，后续可在 M7/M8 进一步统一为“编排层构造、LLM 层纯发送”模式。
- `ApiResult` 的全局成功码和前端现有拦截器契约仍未完全统一，这属于 M7/M9 的联调范围，不影响 M6 后端链路打通。
