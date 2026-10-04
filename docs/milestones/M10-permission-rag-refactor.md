# M10 — 权限架构重构 + RAG 向量检索

> 更新日期：2026-04-16
> 当前状态：代码开发完成，真实环境联调 / 向量链路验收待执行

## 交付物清单
- [x] 新增第三数据源 `vector`，独立 `DataSource` / `SqlSessionFactory` / `TransactionManager`
- [x] 接入 Spring AI Ollama，本地 `bge-m3` embedding 通路打通，增加 1024 维防御校验
- [x] 新增 `meta_embedding` 向量实体、Mapper、`PgVectorTypeHandler`
- [x] 新增 `EmbeddingService`、`EmbeddingController` 与统计 / 检索 / 全量同步能力
- [x] `MetadataService.retrieveForPrompt()` 改为向量优先、关键词兜底的混合检索
- [x] 术语、示例、DDL 入库后自动异步触发向量化；删除同步清理向量记录
- [x] `dim_org` / `sys_user` / `sys_user_org` 实体、Mapper、控制器、权限服务补齐
- [x] `AuthService` 切换到 `sys_user`，JWT 载荷与 `/api/v1/auth/me` 返回支持 `userId` / `role` / `orgGrants`
- [x] `OrgService` 切换到 `dim_org`，补齐 `syncFromBusiness()` / 我的组织树 / 组织账号管理
- [x] `OrchestratorService` 接入组织权限校验，查询前解析有效 `orgId` 集合
- [x] 前端管理域补齐「账号管理」「向量检索管理」「组织人员管理」新交互
- [x] MSW handlers 补齐用户、向量、组织权限相关 mock
- [x] 关闭 `dim_org` 字段冲突问题单
- [x] 后端 `mvn compile && mvn test` 通过
- [x] 前端 `npx tsc --noEmit && npm run build` 通过
- [ ] 在真实 PostgreSQL / Redis / Ollama 环境完成 RAG 与权限链路端到端验收

## 本轮重点完成内容

### 1. 权限架构重构
- 登录身份源切换为 `sys_user`，角色统一为 `ADMIN / MANAGER / USER`。
- 新增 `UserService` 和用户、组织授权接口，支持角色边界控制、组织授权授予/回收、默认组织维护。
- `OrgService` 已改为以 `dim_org` 为主实现，保留旧 `org_tree` 代码但不再作为主路径。
- `OrchestratorService` 在查询前通过 `UserService.resolveAccessibleOrgIds(...)` 做组织权限裁剪，未授权直接拒绝。

### 2. RAG 向量检索落地
- Spring AI + Ollama 已接入本地 `bge-m3`，embedding 只经由 `LLMService.generateEmbedding()` 触发。
- `EmbeddingService` 支持向量生成、异步写入、相似检索、全量同步、统计汇总与删除清理。
- `MetadataService` 已实现问答示例、术语、表描述三类元数据的混合召回策略：
  - 示例：向量优先，不足时关键词兜底
  - 术语：向量优先，不足时关键词兜底
  - 表描述：向量与关键词合并去重
- 元数据保存失败不会因向量化失败而中断主流程，符合 M10 红线。

### 3. dim_org 字段冲突关闭
- 已按真实业务库查询结果确认 `dim_org` 字段为：
  - `id`
  - `org_name`
  - `parent_id`
  - `org_level`
  - `org_code`
  - `is_active`
- 业务库侧 `DimOrg.java`、`OrgService.syncFromBusiness()` 与问题单已全部修正并关闭。
- 问题记录见：[2026-04-16_20-08-00_dim_org_字段问题.md](../issue/database/2026-04-16_20-08-00_dim_org_字段问题.md)

### 4. 前端管理域改造
- `AdminLayout.tsx` 新增「账号管理」「向量检索管理」，并将「组织机构管理」改名为「组织人员管理」。
- `UserManagePage.tsx` 已完成用户列表、筛选、Drawer 编辑、新建、禁用/启用、重置密码。
- `OrgTreePage.tsx` 已改为只读组织树 + 右侧组织账号面板 + 同步按钮。
- `EmbeddingManagePage.tsx` 已完成统计卡片、全量同步、检索测试区域。
- `ExamplesPage.tsx`、`GlossaryPage.tsx`、`DDLUploadPage.tsx` 已补充“已触发向量化”提示。

## 验证结果
```text
cd backend  && mvn compile   ✅
cd backend  && mvn test      ✅
cd frontend && npx tsc --noEmit   ✅
cd frontend && npm run build       ✅

Tests run: 155, Failures: 0, Errors: 0, Skipped: 0
```

## 架构红线合规状态

| 检查项 | 状态 | 说明 |
|------|------|------|
| LLM 只接收元数据 | ✅ | 本轮未引入向 LLM 传递业务结果行的新路径 |
| SQL 必须经过 `SqlGuardService.validate()` | ✅ | 查询主链路未绕过网关校验 |
| Embedding 只通过 `LLMService.generateEmbedding()` | ✅ | Service 层未直接注入 Spring AI Bean |
| Prompt 只存放在 `/prompts/` | ✅ | 本轮未增加硬编码 Prompt |
| 向量化失败不影响主流程 | ✅ | `EmbeddingService` 异步捕获异常并仅记录日志 |
| 查询请求需可审计 | ✅ | 查询主链路审计未被本轮改动绕过 |

## 遗留事项
- 真实 Ollama / pgvector 环境下尚未执行 `embeddings/sync`、`embeddings/search`、元数据保存自动向量化的端到端验收。
- JWT 权限变更后的即时失效策略仍未做 Redis 黑名单或短 TTL 收口，当前仍依赖既有 token 生命周期。
- 前端生产包仍存在 chunk size warning，但不阻塞本轮构建验收。
