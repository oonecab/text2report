# Text2Report 项目状态

> 最后更新：2026-05-04

## 当前里程碑

**M11 — 后 M10 打磨与全栈代码评审（2026-04-27，代码完成）**

M11 已完成本轮代码级开发与全栈评审：

- 认证安全加固：单设备登录 jti 校验、重新登录时旧 session 轮转、Manager 权限边界收紧、组织范围审计访问控制
- 时区处理标准化：后端统一 `Asia/Shanghai` 时区，前后端时间字段对齐
- 查询体验提升：历史查询增强筛选与复用、收藏状态在查询全链路同步、SQL 预览保留字面量与注释格式
- 前端 UI 打磨：收藏页面布局优化、InsightBridge 品牌视觉升级、组织树按用户权限裁剪
- 元数据管理增强：`meta_table` 支持编辑 label/description
- 基础设施：SQL DDL 文件归入版本控制
- **全栈代码评审完成**：产出 `docs/evaluation/code-review-20260427-172225.md`，发现 4 个 P0 项、10 个 P1 项、若干 P2 改进建议
- **SqlGuard 组织范围风险分析完成**：`docs/issue/sqlguard-org-scope-risk-review-20260425_222403.md`，提出元数据驱动的 scope_type 升级方案
- 后端 `mvn test` 221 个用例全部通过（较 M10 的 155 个净增 66 个），前端 `npx tsc --noEmit` 与 `npm run build` 成功

详情见：`docs/milestones/M11-post-m10-refinement.md`

## 新立项需求

### M12 — 多模型配置中心与 Provider Driver 架构（需求分析完成）

基于 M11 中原“多模型接入架构分析”的延伸，本项目已新增一个独立需求：建设后台多模型配置能力，仅允许系统管理员（`ADMIN`）在前端页面维护模型配置，并将配置持久化保存到元数据数据库。该能力同时支持**在线模型**与**本地模型**两类配置入口，由系统按当前激活配置执行后续 LLM 调用，默认模型为 DeepSeek。

当前结论：

- **需求可实现**，且与现有 `LLMService` 接口隔离设计兼容
- **必须在元数据数据库中落表**，至少新增模型配置主表，并建议同步补齐密钥、路由、审计表
- **不能只做前端页面**，还需要同时补齐后端配置域、协议驱动层、密钥安全、审计留痕、缓存一致性
- **建议 M12 先做全局单活模型**，默认 DeepSeek，优先落地在线模型与本地 OpenAI 兼容模型的配置化
- **Embedding 模型暂不并入本次需求**，避免与在线 chat 模型治理混淆

详情见：`docs/milestones/M12-multi-model-configuration-architecture.md`

## 项目当前状态看板

### 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    Text2Report v1.0                      │
│         Natural Language → Safe SQL → Report             │
├─────────────────────────────────────────────────────────┤
│  Frontend (React 18 + TS + Ant Design 5 + ECharts)      │
│  ├─ Query Pages (查询/历史/收藏)                         │
│  ├─ Admin Pages (表/列/术语/JOIN/示例/DDL/审计/用户/向量)│
│  └─ State (Zustand + persist) + Mock (MSW)              │
├─────────────────────────────────────────────────────────┤
│  Backend (Java 21 + Spring Boot 3.2 + MyBatis-Plus)     │
│  ├─ Orchestrator (编排管道)                              │
│  ├─ LLM Service (OpenAI/DeepSeek via OkHttp, @Async)    │
│  ├─ SqlGuard (JSqlParser AST 校验, 多维度拦截)          │
│  ├─ Metadata + RAG (向量优先 + 关键词兜底混合检索)       │
│  ├─ Auth (JWT + Redis Session + BCrypt + RBAC)          │
│  ├─ Rate Limiting (Redis Sorted Set 滑动窗口)            │
│  └─ Audit (异步 upsert, prompt 脱敏, 不可绕过)           │
├─────────────────────────────────────────────────────────┤
│  Infrastructure (Alibaba Cloud Docker)                   │
│  ├─ postgres:15 → text2report (RO) + text2report-meta   │
│  ├─ redis:7 → Cache / Session / Rate Limit              │
│  └─ Ollama → bge-m3 (local embedding)                   │
└─────────────────────────────────────────────────────────┘
```

### 质量指标

| 指标 | 当前值 | 趋势 |
|------|--------|------|
| 后端测试用例 | 221 个 | ↑ (M10: 155) |
| 测试通过率 | 100% | → |
| 前端 TypeScript 编译 | 0 error | → |
| 前端生产构建 | 成功 (含 chunk size warning) | → |
| 已知 P0 问题 | 4 个 (见代码评审报告) | ↑ (新发现) |
| 已知 P1 问题 | 10 个 | ↑ (新发现) |
| 真实环境验收 | 待执行 | → |

### 安全设计纵深

```
Layer 1: HikariCP read-only=true
Layer 2: SqlGuardService AST 非 SELECT 拦截
Layer 3: SqlGuardService 系统 schema/危险函数/SELECT * 拦截
Layer 4: SqlGuardService org_id 范围校验
Layer 5: SqlGuardService LIMIT 自动注入
Layer 6: JDBC queryTimeout=30s
Layer 7: Rate Limiting (Redis Sorted Set 滑动窗口)
Layer 8: JWT + Redis Session 双验证 (单设备登录)
Layer 9: API Key 脱敏落审计 (不可绕过)
```

### 架构红线合规状态

| 检查项 | 状态 | 说明 |
|------|------|------|
| Rule 1: LLM 只看元数据 | ✅ | `summarizeResults(ResultProfile)` 严格执行 |
| Rule 2: SqlGuard 不可绕过 | ✅ | 所有 SQL 必经 `validate()` + `validateOrgScope()` |
| Rule 3: 只读执行 | ✅ | HikariCP `read-only=true` + AST 级拦截 |
| Rule 4: 请求可审计 | ✅ | `audit_query_log` upsert 异步写入，覆盖成功/失败/缓存命中 |
| Rule 5: 结构化输出 | ✅ | `response_format: json_object` 强制 |
| Rule 6: Prompt 模板文件 | ✅ | 所有 prompt 在 `/prompts/` 下版本化 |
| Rule 7: DDL 入口 | ✅ | 元数据通过 DDL parse → confirm 管线入库 |
| Rule 8: 审计全覆盖 | ✅ | 含缓存命中、blocked、error 全状态 |

### 已知待处理问题

| 优先级 | 数量 | 关键项 |
|--------|------|--------|
| P0 | 4 | `application.yml` vector 默认值泄露 IP/密码；`AuthService` 反射调用 `JwtUtil`；JWT token localStorage 存储风险；`ErrorCode.LLM_ERROR` 值不一致 |
| P1 | 10 | 无限循环保护；JSON 拼接改 ObjectMapper；OkHttpClient 复用；前端请求去重；等 |
| P2 | 若干 | PostgreSQL 特有危险函数；集成测试；前端组件测试；等 |

### 真实环境验收状态

| 场景 | 状态 | 说明 |
|------|------|------|
| 权限链路 | ⏳ | 组织-用户-授权 全链路待真实环境验证 |
| RAG 检索 | ⏳ | embeddings/sync、embeddings/search 待真实环境验证 |
| 异步向量化 | ⏳ | DDL/术语/示例保存后自动向量化待验收 |
| 组织同步 | ⏳ | syncFromBusiness 待真实业务库验证 |
| 端到端查询 | ⏳ | 待按 e2e-test-checklist 逐项验证 |

## 已完成里程碑

- M0 — 项目初始化 & 前端骨架搭建
- M1 — 管理域前端页面开发
- M2 — 查询域前端页面开发
- M3 — 前端联调准备（Mock Server）
- M3-Hotfix — 前端安全与质量修复（第一轮）
- M3-Hotfix-R2 — 前端安全与质量修复（第二轮，M4 前置门槛清零）
- **M4 — 后端 Spring Boot 骨架 + 公共模块（2026-04-10）**
- **M4-Hotfix — 安全与质量修复（2026-04-10）**
- **M4-Hotfix-R2 — 安全与质量修复（第二轮）（2026-04-11）**
- **M4-Hotfix-R3 — 安全与质量修复（第三轮修复）（2026-04-11）**
- **M4-Hotfix-R4 — 安全与质量修复（第四轮清理）（2026-04-11）**
- **M5 — 组织 + 元数据服务（2026-04-11）**
- **M5-Hotfix — Codex 审查缺陷修复（2026-04-11）**
- **M5-Hotfix-R2 — Codex 第二轮审查缺陷修复（2026-04-11）**
- **M6 — QueryExecutorService + OrchestratorService 端到端编排（2026-04-11）**
- **M7 — 查询结果组装 + Redis 缓存 + 历史/反馈 API（2026-04-11）**
- **M8 — 网关层 + CORS + 限流 + 审计增强（2026-04-12）**
- **M9 — 前后端联调 + 端到端验收（2026-04-12）**
- **M10 — 权限架构重构 + RAG 向量检索（2026-04-16）**
- **M11 — 后 M10 打磨 + 全栈代码评审（2026-04-27）**

## 里程碑总览

| 编号 | 名称 | 日期 | 状态 |
|------|------|------|------|
| M0 | 项目初始化 & 前端骨架 | — | ✅ 已完成 |
| M1 | 管理域前端页面 | — | ✅ 已完成 |
| M2 | 查询域前端页面 | — | ✅ 已完成 |
| M3 | 前端联调准备（Mock Server） | — | ✅ 已完成 |
| M3-Hotfix | 前端安全与质量修复（第一轮） | — | ✅ 已完成 |
| M3-Hotfix-R2 | 前端安全与质量修复（第二轮） | — | ✅ 已完成 |
| M4 | 后端骨架 + 公共模块 | 2026-04-10 | ✅ 已完成 |
| M4-Hotfix | 安全与质量修复 | 2026-04-10 | ✅ 已完成 |
| M4-Hotfix-R2 | 安全与质量修复（第二轮） | 2026-04-11 | ✅ 已完成 |
| M4-Hotfix-R3 | 安全与质量修复（第三轮） | 2026-04-11 | ✅ 已完成 |
| M4-Hotfix-R4 | 安全与质量修复（第四轮） | 2026-04-11 | ✅ 已完成 |
| M5 | 组织 + 元数据服务 | 2026-04-11 | ✅ 已完成 |
| M5-Hotfix | Codex 审查缺陷修复 | 2026-04-11 | ✅ 已完成 |
| M5-Hotfix-R2 | Codex 第二轮审查缺陷修复 | 2026-04-11 | ✅ 已完成 |
| M6 | LLM + SQL Guard 服务编排 | 2026-04-11 | ✅ 已完成 |
| M7 | 结果组装 + Redis 缓存 + 历史/反馈 API | 2026-04-11 | ✅ 已完成 |
| M8 | 网关 + 认证 + 审计 | 2026-04-12 | ✅ 已完成 |
| M9 | 前后端联调 + 端到端验收 | 2026-04-12 | 🟡 代码完成，待真实环境验收 |
| M10 | 权限架构重构 + RAG 向量检索 | 2026-04-16 | 🟡 代码完成，待真实环境验收 |
| **M11** | **后 M10 打磨 + 全栈代码评审** | **2026-04-27** | 🟡 代码完成，P0 修复待执行 |
| **M12** | **多模型配置中心与 Provider Driver 架构** | **2026-05-04** | 📝 需求分析 / 架构设计完成，待实现 |

## 下一步计划

1. **P0 修复** — 按代码评审报告修复 4 个 P0 问题（凭证泄露、反射调用、token 存储、错误码不一致）
2. **P1 修复** — 逐步处理 10 个 P1 问题
3. **真实环境验收** — 在 Alibaba Cloud 环境执行 M9/M10 遗留的端到端验收场景
4. **M12 实施准备** — 推进多模型配置中心与 Provider Driver 架构，默认 DeepSeek，先支持 OpenAI 兼容模型配置化
5. **后续候选需求** — 元数据驱动的 org scope 控制升级作为 M12 后续候选 / M13 备选项继续保留
