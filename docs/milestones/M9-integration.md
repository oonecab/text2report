# M9 — 前后端联调 + 端到端验收

> 更新日期：2026-04-12
> 当前状态：代码联调完成，真实环境验收待执行

## 交付物清单
- [x] 前端开发环境默认关闭 MSW，切换到真实后端
- [x] 认证接口对齐，补齐 `/api/v1/auth/me`
- [x] 统一响应结构对齐，前端请求层兼容后端 `code=200` 与 `request_id`
- [x] 组织接口路径对齐到 `/api/v1/orgs/*`
- [x] 元数据管理 service 层补齐 glossary / joins / examples / columns 的真实 CRUD
- [x] 查询结果表格与图表组件兼容真实后端列结构
- [x] 产出 Demo 部署指南
- [x] 产出真实环境 E2E 验收清单
- [ ] 真实数据库 / Redis / LLM 环境下逐项完成端到端场景验收

## API 契约差异修复记录

| 模块 | 修复内容 | 结果 |
|------|------|------|
| 认证 | 新增 `/api/v1/auth/me`，登录返回用户角色统一为小写 | ✅ |
| 统一响应 | `ApiResult` 改为 `code=200` / `message=success`，并输出 `request_id` | ✅ |
| 前端请求层 | Axios 拦截器按真实 `ApiResult` 解包，统一附加 `X-Request-ID` | ✅ |
| 组织接口 | 前端从旧 `/v1/org/...` 切换到 `/v1/orgs/...` | ✅ |
| 元数据接口 | 前端 `metadataApi` 增加后端实体到页面模型的映射，DDL 提交对齐 `/confirm` | ✅ |
| 查询结果 | 表格与图表组件兼容 `{ key, title, dataType }` 列定义 | ✅ |
| 历史分页 | 历史接口分页参数与返回结构做兼容归一 | ✅ |

## 本轮验证结果
```text
cd backend  && mvn -q -DskipTests compile   ✅
cd backend  && mvn -q test                  ✅
cd frontend && npm run build                ✅

Tests run: 156, Failures: 0, Errors: 0, Skipped: 0
```

## 发现并修复的问题
- 前端请求拦截器此前假设后端返回 `code='SUCCESS'`，已改为兼容数值型成功码并保持统一解包。
- 前端服务层仍保留一批 Mock 时代的旧路径与旧字段，已集中调整到真实后端 API。
- 后端缺少前端启动时需要的 `/api/v1/auth/me`，已补齐当前用户查询接口。
- 元数据管理页依赖的 joins / examples / glossary / column 更新接口此前未完全闭环，现已由 `MetadataService` 和 `MetadataController` 补齐。
- 查询表格和图表此前按字符串列数组渲染，现已兼容真实列元数据对象。

## 架构红线合规状态

| 检查项 | 状态 | 说明 |
|------|------|------|
| Rule 1: LLM 只看元数据 | ✅ | 本轮仅做契约与接口联调，未引入任何将结果行传给 LLM 的路径 |
| Rule 2: SqlGuard 不可绕过 | ✅ | 查询主链路未修改网关约束 |
| Rule 3: 只读执行 | ✅ | 未引入新的业务库写入入口 |
| Rule 4: 请求可审计 | ✅ | `/auth/me`、查询、历史、反馈、收藏仍走既有审计链路 |
| Rule 5: 结构化输出 | ✅ | 前端按结构化 JSON 响应解包 |
| Rule 6: Prompt 模板文件 | ✅ | 本轮未引入任何硬编码 Prompt |
| Rule 7: DDL 入口 | ✅ | DDL 仍通过 `/metadata/ddl/parse` 与 `/confirm` |
| Rule 8: 审计全覆盖 | ✅ | 查询审计逻辑未被前端联调改动绕过 |

## 遗留问题
- 真实环境下的完整端到端场景尚未逐项执行，需按 [docs/testing/e2e-test-checklist.md](../testing/e2e-test-checklist.md) 补完。
- 前端生产包主 chunk 仍较大，`vite build` 会给出 chunk size warning，但不影响当前 Demo 交付。
