# M3 — 前端联调准备（Mock Server）

## 基本信息
- 开始日期：2026-04-09
- 完成日期：2026-04-09
- 状态：✅ 已完成

## 目标
将分散的内存 Mock 升级为 MSW 2.x 网络层拦截，使前端 axios 调用与真实后端保持完全一致的 HTTP 契约。

## 交付物

### 环境变量
- `.env.development` — 追加 `VITE_USE_MSW=true`
- `.env.production` — 追加 `VITE_USE_MSW=false`
- `.env.example` — 新建，说明所有可用环境变量

### MSW 基础设施
- `public/mockServiceWorker.js` — npx msw init 自动生成
- `src/mocks/browser.ts` — setupWorker 初始化
- `src/mocks/node.ts` — setupServer 备用
- `src/mocks/handlers/index.ts` — 聚合所有 handler
- `src/mocks/handlers/utils.ts` — ok() / okPage() / bizError() / httpError() 工具函数

### MSW Handlers
- `src/mocks/handlers/authHandlers.ts` — 认证 handler（login/logout/me/refresh）
- `src/mocks/handlers/queryHandlers.ts` — 查询 handler（query/history/feedback/favorites）
- `src/mocks/handlers/orgHandlers.ts` — 组织 handler（tree/children CRUD）
- `src/mocks/handlers/metadataHandlers.ts` — 元数据 handler（tables/columns/glossary/joins/examples/ddl）
- `src/mocks/handlers/auditHandlers.ts` — 审计 handler（logs/stats）

### MSW Mock 数据
- `src/mocks/data/orgData.ts` — 组织树数据（华夏集团 → 3子公司 → 9部门）
- `src/mocks/data/queryData.ts` — 6个查询场景 + 15条历史 + 2条收藏
- `src/mocks/data/metadataData.ts` — 3表/19字段、8术语、2JOIN、6示例
- `src/mocks/data/auditData.ts` — 30条审计日志覆盖近30天

### 页面改造
- `src/main.tsx` — 条件启动 MSW worker
- `src/pages/query/FavoritesPage.tsx` — 收藏页面（列表/重新执行/取消收藏）
- `src/pages/admin/AuditLogPage.tsx` — 审计日志页面（统计卡片/筛选/分页/详情Drawer）

### Service 层清理
- `src/services/queryApi.ts` — 清除 USE_MOCK 分支，回归纯 axios
- `src/services/orgApi.ts` — 同上
- `src/services/metadataApi.ts` — 同上
- `src/services/request.ts` — baseURL 改为 '/api'
- `src/services/auditApi.ts` — 新增审计 API 服务

### 文档
- `docs/api-contract.md` — API 契约文档，供后端 M4-M8 参考

## 技术说明

### MSW 启动逻辑
```typescript
if (import.meta.env.VITE_USE_MSW === 'true') {
  const { worker } = await import('./mocks/browser');
  await worker.start({ onUnhandledRequest: 'warn' });
}
```
MSW 通过动态 import 加载，VITE_USE_MSW=false 时不打包 MSW 代码。

### 响应格式
所有 API 响应统一包装：
```json
{ "code": "SUCCESS", "message": "ok", "data": T | null }
```

### SQL 安全模拟
查询 handler 检测写操作关键词（删除/修改/更新/insert/update/delete），返回 422 SQL_GUARD_REJECTED。

### 路由场景匹配
- 含"趋势"→ line chart
- 含"部门"且含"对比/比较"→ bar chart
- 含"排名"或 `top N` → pie chart
- 含"明细/清单" → table
- 含"警告/数据质量" → warning scenario

## 测试记录

| 测试项 | 方法 | 结果 |
|--------|------|------|
| TypeScript 编译 | npx tsc --noEmit | ✅ 零错误 |
| 生产构建 | npm run build | ✅ 成功 |
| MSW Worker 启动 | 浏览器 Console | ✅ 显示启动信息 |
| 登录 Mock | 手动测试 | ✅ admin/user 角色正常 |
| 查询 Mock | 手动测试 | ✅ 场景路由正确 |
| SQL Guard 拒绝 | 手动测试 | ✅ "删除"关键词返回 422 |
| 组织 CRUD | 手动测试 | ✅ 增删改正常 |
| DDL Parse | 手动测试 | ✅ 非 CREATE 返回错误 |
| 审计日志列表 | 手动测试 | ✅ 30条数据显示正常 |
| 审计日志详情 | 手动测试 | ✅ admin 可见完整 SQL |
| Favorites 列表 | 手动测试 | ✅ 显示收藏项 |
| Favorites 重新执行 | 手动测试 | ✅ 跳转 QueryPage |
| Favorites 取消收藏 | 手动测试 | ✅ 乐观更新正常 |

## 遗留问题

（暂无）
