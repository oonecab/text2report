# M7 — 查询结果组装 + Redis 缓存 + 历史/反馈 API

> 完成日期：2026-04-11

## 交付物清单
- [x] `QueryCacheService` 查询结果 Redis 缓存服务
- [x] `OrchestratorService` 集成缓存命中 / 回写逻辑
- [x] `HistoryItem` / `FavoriteItem` / `FeedbackRequest` DTO
- [x] `audit_query_logs` 审计表字段扩展与 Mapper 同步
- [x] `HistoryService` 历史查询服务
- [x] `FeedbackService` 反馈提交服务
- [x] `FavoriteService` 收藏管理服务
- [x] `QueryController` 扩展历史 / 反馈 / 收藏 API
- [x] M7 单元测试补齐
- [x] `docs/STATUS.md` 更新

## 关键实现摘要
- 查询主链路增加 Redis 缓存，按 `md5(question.trim().toLowerCase() + ":" + orgId)` 生成 key，命中时直接返回缓存结果，同时继续写审计日志并标记 `cache_hit=true`。
- 历史查询基于 `audit_query_logs` 分页检索，支持关键词、日期范围和权限隔离；普通用户仅能查看自己的历史记录，且默认隐藏 SQL。
- 收藏和反馈均基于审计表做状态更新，写入前会校验当前用户对 `request_id` 的归属权限。
- 为了支持历史页复用查询结果，审计表新增 `summary` 与 `recommended_chart` 字段，但仍未存储原始结果行；历史详情优先回填缓存中的结构化结果，缓存缺失时回退到审计摘要视图。

## API 契约对齐验证表

| API | 前端 Mock 契约 | 后端实现状态 | 说明 |
|------|------|------|------|
| `POST /api/v1/query` | `QueryResultDTO` | ✅ | 返回结构化 `QueryResultDTO`，并为非 admin 隐藏 SQL |
| `GET /api/v1/query/history` | `PageResult<HistoryItem>` | ✅ | 支持 `page/current`、`pageSize`、`keyword`、`startDate`、`endDate` |
| `POST /api/v1/query/{requestId}/feedback` | 反馈提交 | ✅ | 兼容现有前端 `feedback=good/bad`，也支持 `rating/comment` |
| `GET /api/v1/query/favorites` | `List<FavoriteItem>` | ✅ | 返回收藏列表，按 `favorite_at DESC` 排序 |
| `POST /api/v1/query/{requestId}/favorite` | 添加收藏 | ✅ | 仅允许操作本人历史记录 |
| `DELETE /api/v1/query/{requestId}/favorite` | 取消收藏 | ✅ | 仅允许操作本人历史记录 |
| 统一响应包裹 | 前端 Mock 与真实后端完全一致 | ⚠️ 待 M9 联调统一 | 当前仍沿用后端全局 `ApiResult` 包裹，前端解包兼容性将在 M9 一并收口 |

## schema-meta.sql 变更说明
- 为 `audit_query_logs` 增加 `summary`、`recommended_chart`，用于历史页展示必要摘要信息。
- 增加 `cache_hit`、`feedback_rating`、`feedback_at`、`is_favorite`、`favorite_at`，用于缓存审计、反馈和收藏功能落库。

## 测试结果
```text
mvn -q -DskipTests compile  ✅
mvn -q test                 ✅

Tests run: 141, Failures: 0, Errors: 0, Skipped: 0
```

## 架构红线合规状态

| 检查项 | 状态 | 说明 |
|------|------|------|
| Rule 1: LLM 只看元数据 | ✅ | 缓存和历史仅保存最终结构化结果 / 审计摘要，未将原始结果行传回 LLM |
| Rule 2: SqlGuard 不可绕过 | ✅ | 查询执行仍只能走 `OrchestratorService -> SqlGuardService -> QueryExecutorService` |
| Rule 3: 只读执行路径 | ✅ | M7 未新增任何业务库写路径，收藏/反馈仅写元数据库审计表 |
| Rule 4: 请求可审计 | ✅ | 缓存命中、正常执行、反馈、收藏均落审计或基于审计表更新 |
| Redis 降级不影响核心功能 | ✅ | 缓存读写失败统一降级为 miss，不影响查询主链路 |
| 不使用 Redis `KEYS` | ✅ | 批量失效使用 `SCAN` 遍历前缀删除 |

## 遗留问题
- 前端当前请求封装对统一响应 `code` 字段的成功判定与后端 `ApiResult` 规范仍有差异，这属于 M9 的前后端联调范围，不影响 M7 后端能力落地。
