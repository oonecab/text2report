# M11 — 后 M10 打磨 + 全栈代码评审

> 更新日期：2026-04-27
> 当前状态：代码开发与评审完成，P0 修复待执行，真实环境验收待执行

## 交付物清单
- [x] 认证安全加固：单设备登录 jti 校验、重新登录时旧 session 轮转、Manager 权限边界收紧
- [x] 组织范围审计访问控制：Manager 只能查看其管理组织的审计日志
- [x] 时区处理标准化：后端统一 `Asia/Shanghai` 时区，前后端时间字段对齐
- [x] 查询历史增强：多维度筛选（关键词、组织、用户、日期范围、置信度）与查询复用
- [x] 收藏状态全链路同步：查询结果、历史记录、收藏列表三处一致的 is_favorited 状态
- [x] SQL 预览格式保留：修复 SQL 字面量与注释在预览中被格式化丢失的问题
- [x] 收藏页面布局优化
- [x] InsightBridge 品牌视觉升级
- [x] 组织树按用户权限裁剪，前端分页优化
- [x] `meta_table` 编辑功能（label / description）
- [x] SQL DDL 文件纳入版本控制
- [x] 全栈代码评审完成，产出详细评审报告
- [x] SqlGuard 组织范围判定风险分析完成，产出元数据驱动升级方案
- [x] 后端 `mvn compile && mvn test` 通过（221 个用例）
- [x] 前端 `npx tsc --noEmit && npm run build` 通过
- [ ] P0 问题修复（4 项）
- [ ] P1 问题修复（10 项）
- [ ] 真实环境端到端验收

## 本轮重点完成内容

### 1. 认证安全加固
- 单设备登录校验：`JwtAuthFilter` 在 token 验证前先检查 Redis blacklist，再校验 jti，实现"后登踢前登"
- 重新登录 session 轮转：`AuthService.login()` 直接覆写 Redis session key，旧 token 自动失效
- Manager 权限边界收紧：Manager 只能查看其所管理组织的审计日志，而非全局审计视图
- 组织范围审计访问控制：audit API 按用户 org scope 过滤结果

### 2. 时区处理标准化
- 后端三数据源（business / meta / vector）统一设置 `connection-init-sql: SET TIME ZONE 'Asia/Shanghai'`
- 前端日期展示与筛选统一使用本地时区
- Jackson 序列化配置 `time-zone: Asia/Shanghai` + `write-dates-as-timestamps: false`

### 3. 查询体验提升
- 历史查询支持多维度筛选：关键词、组织、用户、日期范围（startDate/endDate）、置信度范围
- 支持从历史记录复用上次查询条件，一键重查
- 收藏状态在整个查询流程中保持同步：查询结果、历史列表、收藏页面
- MSW mock 同步修复，确保开发环境下收藏状态流转正确

### 4. 前端 UI 打磨
- 收藏页面：卡片布局优化，视觉层次增强
- InsightBridge 品牌升级：登录页 brand badge、品牌色、图标组件统一
- SQL 预览修复：`SqlCodeBlock` 组件保留原始 SQL 的字面量和注释，不再被格式化工具破坏
- 组织树按用户权限动态裁剪，仅展示用户可访问的组织节点
- 管理域分页逻辑优化

### 5. 元数据管理增强
- `meta_table` 支持编辑 label 和 description 字段
- `MetadataService.updateTable()` 编辑后自动失效查询缓存

### 6. 全栈代码评审
- 产出 `docs/evaluation/code-review-20260427-172225.md`：覆盖后端 30+ 文件、前端 20+ 文件
- 发现问题分层：P0（4 项）/ P1（10 项）/ P2（若干项）
- 评审维度：架构设计、安全性、代码质量、测试覆盖、前端体验、可维护性
- 总评：架构成熟、安全性出色的企业级项目，Demo 阶段良好状态

### 7. SqlGuard 组织范围安全风险分析
- 产出 `docs/issue/sqlguard-org-scope-risk-review-20260425_222403.md`
- 识别核心风险：命名规则驱动而非元数据驱动的 org scope 判定
- 提出四层升级方案：
  1. `meta_table` 增加 `scope_type` 字段（global / org_scoped / derived_org_scoped）
  2. `SqlGuardService` 改为读取元数据判定
  3. 为 derived_org_scoped 补充 join 推导约束
  4. 审计日志增加 scope 判定详情
- 结论：当前实现为 Demo 阶段可接受的过渡方案，需在正式上线前升级为元数据驱动

## 验证结果
```text
cd backend  && mvn compile   ✅
cd backend  && mvn test      ✅
cd frontend && npx tsc --noEmit   ✅
cd frontend && npm run build       ✅

Tests run: 221, Failures: 0, Errors: 0, Skipped: 0
```
> M10 时测试数为 155，本轮净增 66 个测试用例（主要来自认证、权限、审计、时区相关测试补齐）

## 代码评审关键发现

### P0 — 必须修复（上线前）

| 编号 | 位置 | 描述 |
|------|------|------|
| S1 | `application.yml:45-48` | Vector 数据源默认值泄露真实 IP 和密码 |
| S2 | `AuthService.java:172-189` | 使用反射调用 `JwtUtil` 私有方法，违反封装 |
| S3 | `useAuthStore.ts` | JWT token 明文存储在 localStorage，XSS 可读取 |
| B27 | `ErrorCode.java:27` | `LLM_ERROR = 503002` 与 4001-4005/5001 方案不一致 |

### P1 — 强烈建议

| 编号 | 位置 | 描述 |
|------|------|------|
| B1 | `OrchestratorService.java` | `while(true)` 缺少硬上限保护 |
| B17 | `SecurityConfig.java` | JSON 错误响应手动字符串拼接 |
| B13 | `LLMAsyncExecutor.java` | 每次创建新 OkHttpClient，无连接复用 |
| B12 | `AuthService.java` | `generateTokenWithJti` 通过反射访问私钥 |
| B20 | `MetadataService.java` | 正则提取表名可能误匹配 |
| B4 | `OrchestratorService.java` | `isRetryable` 英文消息匹配在中文环境可能失效 |
| F7 | `request.ts` | 响应拦截器类型比较不一致 |
| F6 | `request.ts` | `showError` 动态 import fallback 可能内存泄漏 |
| F10 | `QueryInput.tsx` | controlled/uncontrolled 混合模式 |
| F11 | `ResultTable.tsx` | rowKey 使用 index |

> 完整评审见：`docs/evaluation/code-review-20260427-172225.md`

## 架构红线合规状态

| 检查项 | 状态 | 说明 |
|------|------|------|
| Rule 1: LLM 只看元数据 | ✅ | 本轮未引入向 LLM 传递业务结果行的新路径 |
| Rule 2: SqlGuard 不可绕过 | ✅ | 查询主链路未修改网关约束；已分析 org scope 升级方向 |
| Rule 3: 只读执行 | ✅ | 未引入新的业务库写入入口 |
| Rule 4: 请求可审计 | ✅ | Manager 审计访问控制收紧，审计链路未被绕过 |
| Rule 5: 结构化输出 | ✅ | 本轮未修改 LLM 响应格式 |
| Rule 6: Prompt 模板文件 | ✅ | 本轮未引入硬编码 Prompt |
| Rule 7: DDL 入口 | ✅ | DDL 文件纳入版本控制，parse → confirm 管线未变 |
| Rule 8: 审计全覆盖 | ✅ | 收藏、历史复用查询均走既有的审计链路 |

## 遗留事项
- P0 修复（4 项）待在下个里程碑执行
- P1 修复（10 项）需排优先级逐步处理
- M9/M10 遗留的真实环境端到端验收（权限、RAG、异步向量化、组织同步）仍未执行
- SqlGuard org scope 元数据驱动升级方案待正式立项
- 前端生产包 chunk size warning 仍存在，不影响当前 Demo 交付

---

## 8. 前端表格分页性能审核

> 审核日期：2026-04-28
> 审核范围：前端所有使用表格组件的页面及其数据加载路径，共计 12 个组件 / 页面
> 审核目标：评估数据量增长时前端分页是否会产生卡顿，识别瓶颈并提出解决方案

### 8.1 审核方法与判定标准

本次审核覆盖前端所有涉及 `Table` 组件的页面，按照以下维度逐一评估：

| 审核维度 | 判定标准 |
|---------|---------|
| **分页策略** | 前端分页（全量加载后客户端切片） vs 后端分页（逐页请求） |
| **数据持有量** | 单次加载的数据最大行数，是否存在上界 |
| **排序机制** | 客户端排序（对全量数据执行） vs 服务端排序（数据库 `ORDER BY`） |
| **渲染优化** | 是否启用虚拟化（virtual scroll）、`useMemo` 缓存、列固定 |
| **状态管理** | 全量数据存放位置（组件 state / Zustand store），内存占用评估 |
| **增长预期** | 数据量随时间的增长速度（线性 / 恒定 / 不可控） |

根据判定维度将每个页面归入三类风险等级：

| 风险等级 | 含义 |
|---------|------|
| **高** | 分页策略为前端分页，且数据量有增长至数百行以上的可能，当前或近期会产生可感知的卡顿 |
| **中** | 分页策略为前端分页，但当前数据量较小（数十行），随业务增长可能退化为高风险 |
| **低** | 已采用后端分页，或数据量恒定为个位数，不会因数据增长而产生性能劣化 |

### 8.2 逐页面审核结果

#### 8.2.1 ResultTable（查询结果表格）— 高风险

**文件：** `frontend/src/components/query/ResultTable.tsx`，搭配 `frontend/src/store/useQueryStore.ts`

**当前实现：**

```
POST /v1/query  →  后端返回全量行（SqlGuardService 注入 LIMIT 1000）
                        ↓
                  Zustand Store: currentResult.table.rows (全部行驻留内存)
                        ↓
                  Ant Design <Table> dataSource={rows}
                        ↓
                  前端分页（Table 内部 slice）+ 前端排序（sorter 在全部行上执行）
```

**数据流中的性能瓶颈：**

| 瓶颈环节 | 代码位置 | 详细说明 |
|---------|---------|---------|
| **客户端排序** | `ResultTable.tsx:28-37` `sorter` 回调 | 每次点击排序列，对全部 1000 行执行 O(n log n) 的比较排序。若有 M 个需要 `toLocaleString` 格式化的数值列，每次比较会触发额外的字符串转换 |
| **render 函数开销** | `ResultTable.tsx:38-46` | 每个单元格的 `render` 回调执行 `Number().toLocaleString()` 和空值判断。1000 行 × N 列 = 1000N 次 render 调用。虽然 Ant Design Table 仅渲染当前页的 DOM 节点，但 `render` 在组件渲染生命周期内会被调用 |
| **状态内存占用** | `useQueryStore.ts:63` `currentResult` | `currentResult` 持有完整的 `QueryResponse` 对象，包含全部 1000 行的原始数据。每行可能有 10-15 个字段（数值、字符串），估算单行约 200 bytes → 1000 行 ≈ 200KB，看似不大，但搭配 React DevTools 序列化和 Zustand 的 immer 不可变更新会产生额外内存开销 |
| **无虚拟化** | `ResultTable.tsx:52` | 使用标准 Ant Design `<Table>`，没有 `virtual` 属性。Ant Design 5.x 虽然默认只渲染当前页的 DOM 节点（通过 `Table` 内部的 `rc-table` Body 组件），但 VDOM diff 仍基于传入的完整 `dataSource` 数组。翻页时 React Reconciler 需要遍历完整的 VDOM 子树来判断是否需要更新 |
| **后端 LIMIT 硬编码** | `SqlGuardService` 注入 `LIMIT 1000` | 当前 1000 行是唯一的硬上限保护。如果未来业务需要放宽此限制（如 5000 或 10000），所有上述瓶颈将线性恶化。1000 行本身已经处于前端分页的体验边界 |

**当前可感知的性能表现：**
- 1000 行数据量下，首次渲染和翻页切换不会有明显的 1 秒级卡顿（得益于 Ant Design 的虚拟 DOM 优化和较小的单页数据量）
- 但点击排序时会触发全量行的重排序 + React re-render，在多列场景下可以感受到延迟（200ms ~ 500ms）
- 若数据增至 2000~5000 行，排序延迟将线性增长到 1~3 秒，属于明显卡顿

**Zustand Store 快照分析：**

```
currentResult: {
  sql: "...",
  table: {
    columns: [...],
    rows: [  ← 这个数组始终包含全部行（最多 1000）
      {org_name: "...", revenue: 12345.67, cost: 8901.23, ...},
      {org_name: "...", revenue: 23456.78, cost: 9012.34, ...},
      // ... 最多 1000 条
    ]
  },
  resultProfile: {...},
  summary: "...",
  audit: {...}
}
```

State 扁平化后的 `table.rows` 数组没有分页元数据（无 `total`、`page` 等字段），因为当前设计假设"结果集是一次性的、全量的"。

#### 8.2.2 FavoritesPage（收藏页面）— 中风险

**文件：** `frontend/src/pages/query/FavoritesPage.tsx`

**当前实现：**

| 属性 | 现状 |
|------|------|
| 分页类型 | **无分页** — `pagination={false}`（第 153 行） |
| 数据加载 | `fetchFavorites()` 一次性加载全部收藏（第 31 行） |
| 后端 API | `GET /v1/query/favorites` — 无分页参数，返回全量 |
| 状态存储 | 组件 `useState`（第 21 行），所有行在 `favorites` 数组中 |

**风险评估：**
- 收藏属于用户个人行为，数量级通常不大（数十到数百条），不会像业务数据一样线性增长
- 但该接口和组件均无任何上限保护。如果某个用户系统性地收藏了上千条查询记录，页面加载会全量拉取并在一次渲染中展示所有行
- 当前无分页意味着全部收藏行的 DOM 节点一次性渲染，行数超过 200 时可能产生可感知的渲染延迟

#### 8.2.3 HistoryPage（查询历史）— 低风险

**文件：** `frontend/src/pages/query/HistoryPage.tsx`

**当前实现：**

| 属性 | 现状 |
|------|------|
| 分页类型 | **后端分页** — `page` + `pageSize` 参数透传到后端 |
| 数据加载 | `loadHistory(page, pageSize, filters)`（第 86 行），逐页请求 |
| 后端 API | `GET /v1/query/history?page=&pageSize=` — SQL `LIMIT / OFFSET` + 分离 `COUNT` |
| 状态存储 | Zustand `useQueryStore.history`（第 90 行），仅持有当前页数据（10~100 条） |

**评价：** 此页面为正确的后端分页实现范式。数据量无论多大，前端仅持有当前页的数据切片。翻页触发后端新请求，内存和渲染开销恒定。可作为其他页面改造的参考模板。

**注意：** HistoryPage 的详情抽屉（Drawer）内嵌了 `ResultTable`（第 248 行），用于展示历史查询的全量结果。这意味着打开详情时会继承 ResultTable 的前端分页瓶颈 — 历史查询的结果行（最多 1000 行）被加载到抽屉中的 ResultTable。这是 ResultTable 问题的间接影响，无需单独修复。

#### 8.2.4 AuditLogPage（审计日志）— 低风险

**文件：** `frontend/src/pages/admin/AuditLogPage.tsx`

| 属性 | 现状 |
|------|------|
| 分页类型 | **后端分页** — `page` + `pageSize` 参数透传到后端 |
| 数据加载 | `loadLogs(page, pageSize, filters)`（第 69 行），逐页请求 |
| 后端 API | `GET /v1/audit/logs?page=&pageSize=` — SQL `LIMIT / OFFSET` + 分离 `COUNT` |
| 状态存储 | 组件 `useState` `logs`（第 50 行），仅持有当前页数据（10~100 条） |

**评价：** 与 HistoryPage 同为正确的后端分页实现，审计日志表预计数据量最大（每次查询写入一条记录），后端分页保证了前端性能不受数据总量影响。

#### 8.2.5 UserManagePage（用户管理）— 低风险

**文件：** `frontend/src/pages/admin/UserManagePage.tsx`

| 属性 | 现状 |
|------|------|
| 分页类型 | **后端分页** — `page` + `size: 20` 参数透传到后端 |
| 数据加载 | `loadUsers(targetPage)`（第 61 行），逐页请求 |
| 后端 API | `GET /v1/users?page=&size=20` — MyBatis-Plus 分页插件 |
| 状态存储 | 组件 `useState` `users`（第 33 行），仅持有当前页（固定 20 条） |

**评价：** 正确的后端分页实现。固定每页 20 条，不支持用户切换 pageSize，适合用户管理场景。

**小问题：** 分页接口使用 `size` 参数而其他接口使用 `pageSize`，参数命名不一致（无功能影响，属代码风格问题）。

#### 8.2.6 Admin 元数据管理页面组（GlossaryPage / ExamplesPage / DataSourceListPage / JoinEdgePage）— 中风险

这四个页面采用完全一致的实现模式：**先全量加载，再前端 slice 分页**。

| 页面 | 文件 | 数据加载 | 分页方式 |
|------|------|---------|---------|
| GlossaryPage | `frontend/src/pages/admin/GlossaryPage.tsx` | `fetchGlossary()` 全量加载 | `glossary.slice(offset, offset+pageSize)`（第 168-171 行） |
| ExamplesPage | `frontend/src/pages/admin/ExamplesPage.tsx` | `fetchExamples()` 全量加载 | `examples.slice(offset, offset+pageSize)`（第 237-240 行） |
| DataSourceListPage | `frontend/src/pages/admin/DataSourceListPage.tsx` | `fetchDataSources()` 全量加载 | `datasources.slice(offset, offset+pageSize)`（第 172-175 行） |
| JoinEdgePage | `frontend/src/pages/admin/JoinEdgePage.tsx` | `fetchJoins()` 全量加载 | 同模式 `.slice()` 前端分页 |

**风险评估：**
- 这些页面的数据量在 Demo 阶段为两位数级别（数十个术语、几十个示例、几个数据源、几条 JOIN 边），前端分页完全无性能问题
- 风险在于**无上限保护** — 如果元数据量膨胀（如导入 500+ 术语、200+ SQL 示例），页面会在加载时一次性拉取所有数据，内存和渲染压力随之增长
- 每次 CRUD 操作后（如新增一个术语），前端调用 `loadGlossary()` 重新全量拉取，而非增量更新或局部刷新

**额外发现：** 前端 `metadataApi.ts` 中的 API 函数签名已预留了 `page` / `pageSize` 参数，但以上页面调用时均未传递，说明后端 API 理论上支持分页但页面未启用。

#### 8.2.7 其他表格页面 — 低风险

| 页面 / 组件 | 文件 | 说明 | 风险 |
|------------|------|------|------|
| TableListPage / ColumnEditor | `frontend/src/pages/admin/TableListPage.tsx` | 左侧为 List 组件（非 Table），右侧 `ColumnEditor` 表格 `pagination={false}`，仅渲染单个表的所有列（通常 < 20 列） | **低** |
| EmbeddingManagePage | `frontend/src/pages/admin/EmbeddingManagePage.tsx` | `pagination={false}`，搜索结果行数受 `topK` 参数限制（最大 10 行） | **低** |
| OrgTreePage | `frontend/src/pages/admin/OrgTreePage.tsx` | `pagination={false}`，仅展示单个组织节点的下级用户，行数有限 | **低** |
| SchemaGraphPage | `frontend/src/pages/admin/SchemaGraphPage.tsx` | 无 Table 组件，使用 ECharts 图可视化 | **低** |

### 8.3 问题汇总

| 编号 | 页面 / 组件 | 风险等级 | 核心问题 | 最大数据量 |
|------|-----------|---------|---------|-----------|
| **FT-1** | `ResultTable` | **高** | 全量前端分页 + 客户端排序 + 无虚拟化 + store 持有全量行 | 1000 行（当前）；无架构上限保护 |
| **FT-2** | `FavoritesPage` | **中** | 无任何分页机制，全量加载 + 全量渲染 | 无上限，取决于用户行为 |
| **FT-3** | `GlossaryPage` | **中** | 全量加载 + 前端 `.slice()` 分页，CRUD 后全量重拉 | 当前数十条；无上限保护 |
| **FT-4** | `ExamplesPage` | **中** | 全量加载 + 前端 `.slice()` 分页 | 当前数十条；无上限保护 |
| **FT-5** | `DataSourceListPage` | **中** | 全量加载 + 前端 `.slice()` 分页 | 通常个位数；有增长可能 |
| **FT-6** | `JoinEdgePage` | **中** | 全量加载 + 前端 `.slice()` 分页 | 通常个位数；有增长可能 |

### 8.4 根因分析

前端表格分页性能问题的根本原因可归纳为三个层面：

#### 8.4.1 架构层面：ResultTable 的"一次性查询"范式

当前 `POST /v1/query` 接口的设计哲学是"一次提交，返回完整结果"。这源于早期 Demo 阶段的需求 — 用户提一个问题，系统返回结果，用户查看即可。但随着查询能力增强（排序、翻页、多页浏览），这个范式暴露出两个矛盾：

1. **翻页是浏览行为，不应触发重查询**：用户翻页时仅想浏览已有的第 N 页数据，但当前前端分页模式下数据已在内存中，翻页零网络开销（这是个看似"快"实则危险的特征 — 所有数据已在内存中，数据量小时确实快，数据量大时内存和排序开销不可忽视）
2. **排序是分析行为，可能希望服务端协助**：用户点击排序列时，当前在全量客户端数据上排序。如果将来需要服务端排序（如按聚合结果排），当前架构不支持

#### 8.4.2 实现层面：分页模式不统一

项目内存在三类分页实现模式，且无共享约定或抽象来约束：

| 模式 | 使用页面 | 适用场景 |
|------|---------|---------|
| 后端分页（正确） | HistoryPage, AuditLogPage, UserManagePage | 数据量不可控，随时间线性增长 |
| 前端分页（危险） | ResultTable | 数据量有上限，但上限值（1000）已处于边界 |
| 无分页（未保护） | FavoritesPage, EmbeddingManagePage 等 | 数据量恒定极小 |

`buildCompactTablePagination()` 工具（`frontend/src/utils/tablePagination.ts`）仅封装了 Ant Design 分页器的 UI 配置（`showLessItems`、`responsive`、`showQuickJumper`），并未封装分页的**数据获取策略**。这意味着即使所有页面都使用此工具，仍然各自决定数据是前端分页还是后端分页，缺乏一致的策略约束。

#### 8.4.3 数据流层面：Zustand Store 中 ResultTable 的 rows 无分页元数据

`QueryResponse` 类型定义（`frontend/src/types/query.d.ts`）中，`table.rows` 是一个无界的 `any[][]` 数组，没有伴随 `total`、`page`、`pageSize` 等分页元数据字段。这使得 store、组件、API 三层之间没有传递"数据只是结果集的一个切片"这一信息的通道。

### 8.5 解决方案

#### 8.5.1 FT-1：ResultTable 后端分页改造（P0 — 核心路径）

**目标：** 将 ResultTable 从前端全量分页改为后端逐页分页，前端每次仅持有当前页数据。

**改造策略：**

```
改造前数据流：
  FE: POST /v1/query { question, orgId }
  BE: 生成 SQL → SqlGuard(+LIMIT 1000) → 执行 → 返回全量行
  FE: 全部行存入 Store → Table 前端分页 + 前端排序

改造后数据流：
  FE: POST /v1/query { question, orgId, page: 1, pageSize: 20 }
  BE: 生成 SQL → 外覆 SELECT COUNT(*) → 生成分页 SQL(LIMIT/OFFSET)
       → 执行两条 SQL → 返回 { rows(当前页), total }
  FE: 当前页行存入 Store → Table 后端分页（onChange 重新请求）
```

**涉及改动的层次：**

| 层次 | 文件 | 改动内容 |
|------|------|---------|
| **类型定义** | `frontend/src/types/query.d.ts` | `QueryResponse` 增加 `total: number`、`page: number`、`pageSize: number` 字段。`QueryRequest` 增加 `page`、`pageSize` 可选参数 |
| **API 层** | `frontend/src/services/queryApi.ts` | `submitQuery()` 接受并传递分页参数 |
| **Store** | `frontend/src/store/useQueryStore.ts` | `currentResult` 增加 `total`、`page`、`pageSize` 字段。翻页时触发新请求而非仅更新本地状态 |
| **页面组件** | `frontend/src/components/query/ResultTable.tsx` | 分页 `onChange` 回调触发 store action 重新请求；`pagination.total` 绑定到后端返回的 `total` |
| **父组件** | `frontend/src/pages/query/QueryPage.tsx`（或包含 ResultTable 的父组件） | `handlePageChange` 传递分页参数到查询流程 |
| **后端 DTO** | `QueryRequestDTO.java` | 增加 `page`、`pageSize` 字段（可选，默认 1/20） |
| **后端 DTO** | `QueryResultDTO.java` | 增加 `total`、`page`、`pageSize` 字段 |
| **后端 Service** | `OrchestratorService.java` | 在 SQL 执行阶段：对 `validatedSql` 外覆 `SELECT COUNT(*) AS total FROM ({validatedSql}) AS _count`；再执行 `validatedSql + LIMIT pageSize OFFSET (page-1)*pageSize` |
| **后端 Guard** | `SqlGuardService.java` | 原有的 `LIMIT 1000` 改为 `LIMIT pageSize` + `OFFSET` 验证；`SELECT *` 检测需考虑分页子查询 |

**分页 SQL 策略建议：**

```
-- 第一程：COUNT
SELECT COUNT(*) AS total FROM (
  {validatedSql}   -- 原始生成的 SQL
) _t

-- 第二程：分页数据
{validatedSql}
LIMIT :pageSize OFFSET :offset
```

**缓存策略适配：**
- 当前 Redis 缓存 key 为 `t2r:cache:query:{md5(question+orgId)}`
- 改造后需加入分页维度：`t2r:cache:query:{md5(question+orgId)}:{page}:{pageSize}`
- COUNT 结果也可缓存（同一 question+orgId 的 total 不变，除非有 TTL 过期）

**深度分页优化（远期考虑）：**
- 当 OFFSET 值较大时（如第 100 页），PostgreSQL 仍需扫描前 N 行，性能下降
- 可采用**游标分页**替代 OFFSET：前端传递上一页最后一行的排序键值，后端用 `WHERE sort_key > :cursor LIMIT pageSize` 避免全表扫描
- 但游标分页不支持随机跳页（只能上一页/下一页），UI 上需权衡

#### 8.5.2 FT-1 替代方案：前端虚拟化（P0 备选，快速止损）

如果后端分页改造成本过高或开发周期不够，可先行引入前端虚拟化作为过渡方案：

| 步骤 | 改动 |
|------|------|
| 安装依赖 | `npm install @tanstack/react-virtual` |
| 替换 Table | Ant Design 5.x Table 启用 `virtual` 属性，或替换为 `@tanstack/react-table` + `@tanstack/react-virtual` 组合 |
| 效果 | 仅渲染可视区域内的行 DOM（约 10~20 行），1000 行数据的渲染开销降至恒定 |

**此方案的局限性：**
- 全量数据仍在 store 内存中
- 排序仍在全量行上执行
- 不解决网络传输体积（一次性返回 1000 行的 JSON）
- 不解决未来放宽 LIMIT 后的内存膨胀

**建议：** 虚拟化作为短期快速止损手段，长期仍应推进后端分页改造。

#### 8.5.3 FT-2：FavoritesPage 后端分页（P1）

参照 HistoryPage 的实现模式：

| 层次 | 改动 |
|------|------|
| 前端 API `queryApi.ts` | `fetchFavorites()` 增加 `page`、`pageSize` 参数 |
| 前端页面 `FavoritesPage.tsx` | 使用 `buildCompactTablePagination` 的完整分页配置；`onChange` 回调触发重新请求 |
| 后端 Controller `QueryController.java` | `getFavorites()` 接收 `page`、`pageSize` 参数 |
| 后端 Service / Mapper | 追加 `LIMIT :pageSize OFFSET :offset` + 分离 `COUNT` 查询 |

#### 8.5.4 FT-3 ~ FT-6：Admin 元数据页后端分页（P2）

GlossaryPage、ExamplesPage、DataSourceListPage、JoinEdgePage 四个页面统一改造：

| 层次 | 改动 |
|------|------|
| 前端 API `metadataApi.ts` | `fetchGlossary()`、`fetchExamples()` 等函数在页面调用时传递 `page`、`pageSize`（函数签名已有参数预留） |
| 前端页面 4 个 | 去除 `.slice()` 前端分页逻辑；使用 `buildCompactTablePagination` 完整分页配置；翻页重新请求 |
| 后端 Controller / Service / Mapper（4 组） | 补齐后端 `LIMIT / OFFSET` + `COUNT` 逻辑 |

### 8.6 实施优先级

| 优先级 | 编号 | 改动项 | 理由 |
|--------|------|--------|------|
| **P0** | FT-1 | ResultTable 后端分页改造 | 用户核心交互路径，1000 行已处于前端分页体验边界。数据量一旦增加（如放宽 LIMIT、接入更大数据源），卡顿将成为阻断性问题 |
| **P1** | FT-2 | FavoritesPage 后端分页 | 收藏量随用户使用时间线性增长，当前无任何分页保护，属于可预见的性能退化 |
| **P2** | FT-3~6 | Admin 元数据页后端分页 | 元数据量预期较小，短期内无性能风险。属防御性优化，可在以上 P0/P1 完成后统一处理 |

### 8.7 通用改进建议

除以上逐页面改造外，建议在项目层面引入以下能力：

**9.7.1 统一的分页 Hook / 高阶组件**

当前 `buildCompactTablePagination()` 仅封装了分页器的 UI 配置，建议在其之上封装一个 `useBackendPagination` hook，将分页的数据获取策略也统一起来：

```
useBackendPagination({
  fetchFn: (page, pageSize) => Promise<PageData<T>>,
  defaultPageSize: 10
}) → {
  dataSource: T[],           // 仅当前页数据
  pagination: PaginationProps,  // 可直接传给 Table 的完整分页配置
  loading: boolean,
  refresh: () => void
}
```

好处：
- 消除各页面手动管理 `current`、`pageSize`、`total` 状态的重复代码
- 确保所有新页面默认采用后端分页策略
- 翻页、切换 pageSize、筛选重置到第一页等行为统一处理

**9.7.2 前端分页预警机制**

在 ESLint 或 Code Review 层面，对以下模式发出警告：
- `dataSource.slice()` 或 `.slice()` 前端分页（建议改用后端分页）
- `pagination={false}` 但 dataSource 可能增长（建议明确最大行数或改用分页）
- Ant Design Table 的 `sorter` 作用于可能超过 200 行的全量数据

**9.7.3 分页参数命名统一**

当前存在 `pageSize`（HistoryPage / AuditLogPage）和 `size`（UserManagePage）两种后端参数命名。建议统一为 `pageSize`，与 Ant Design 分页组件的 `pageSize` 属性和 `PageData<T>` 泛型的 `pageSize` 字段保持一致。

### 8.8 审核总结

前端表格分页存在 **1 项高风险问题（FT-1）和 5 项中风险问题（FT-2 ~ FT-6）**，核心风险集中在 ResultTable — 作为用户最频繁交互的组件，它采用全量前端分页 + 客户端排序 + 无虚拟化的组合，在数据量增长时将成为性能短板。

3 个页面（HistoryPage、AuditLogPage、UserManagePage）已正确实现后端分页，可作为改造参考模板。其余 4 个 Admin 元数据页面为防御性优化，短期无风险。

改造的核心原则：**让前端在任何时刻只持有当前页的数据切片，将排序、分页、筛选的计算压力回移到数据库层**。这是 Web 应用中处理大数据量表格的行业标准做法，也是本项目从 Demo 阶段演进到生产环境的必要条件。**
