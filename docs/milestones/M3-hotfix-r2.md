# M3-Hotfix-R2 — M3 修复第二轮（M4 前置门槛清零）

## 基本信息
- 开始日期：2026-04-09
- 完成日期：2026-04-10
- 状态：✅ 已完成
- 前置：M3-Hotfix（部分修复，遗留 CRITICAL/HIGH 问题）

## 修复背景

本轮修复由 Codex 全面验收检查触发，共发现 1 个 CRITICAL、3 个 HIGH、2 个 MEDIUM 问题
及若干 P2 UI 问题，导致无法进入 M4。

## 本次修复内容

### P0 — 功能回归修复
- [x] P0-1：queryApi.ts 响应二次解包修复，核心查询链路恢复正常
- [x] P0-2：request.ts 认证 token 来源统一至 Zustand store，删除所有 localStorage 认证操作
- [x] P0-3：退出登录先调 `/api/v1/auth/logout` API，再清 store
- [x] P0-4：查询/历史/收藏响应按角色剔除 `sql` 字段（普通用户不接收）
- [x] P0-5：MSW SQL Guard 扩充禁词（DROP/TRUNCATE/EXEC/xp_cmdshell/MERGE/ALTER 等），大小写不敏感

### P1 — 安全与功能修复
- [x] P1-1：authHandlers.ts /auth/me 改为精确 token-user 映射，修复 getTokenRole 伪造漏洞
- [x] P1-2：HistoryPage feedback 字段落地，提交反馈后列表状态正确更新
- [x] P1-3：OrgTreeEditor 选中 id 类型修复，操作按钮显示时机修正

### P2 — UI/UX 修复
- [x] P2-1：AdminLayout Sider 改为 theme="dark"
- [x] P2-2：QueryPage 空状态补充示例问题标签和错误重试按钮
- [x] P2-3：FavoritesPage 表格补全 locale/ellipsis
- [x] P2-4：LoadingSkeleton 重写为"摘要卡 + 图表块 + 表格行"结构
- [x] P2-5：ResultTable rowKey 修复
- [x] P2-6：AuditLogPage 详情 Drawer 调用详情接口展示完整信息

## 关键架构决策

### 认证链路设计（Demo 阶段最终方案）
- token 存 Zustand store 内存（刷新需重登，Demo 阶段可接受）
- request.ts 拦截器从 `useAuthStore.getState().token` 读取（不走 localStorage）
- 退出登录：`authApi.logout()` → `useAuthStore.logout()` → navigate('/login')
- MSW token-user 精确映射：`mock-jwt-{role}-token` → 对应用户对象
- 后端就绪后：关闭 VITE_USE_MSW，前端代码零改动

### SQL 字段权限分层
- admin 用户：响应包含 `sql` 字段，UI 层 Collapse 展示
- 普通用户：MSW handler 不返回 `sql` 字段（undefined），UI 层 SqlPreview 不渲染
- 后端就绪后：由后端按角色剔除字段，前端类型定义 `sql?: string` 保持兼容

## 验收测试记录

| 测试项 | 测试方法 | 期望结果 | 实际结果 |
|--------|---------|---------|---------|
| TS 编译 | npx tsc --noEmit | 0 错误 | ✅ 0 错误 |
| 生产构建 | npm run build | 成功 | ✅ 成功 |
| admin 登录 | 用户名 admin / 密码 admin123 | 进入管理域，token 在内存 | ✅ 通过 |
| demo 登录 | 用户名 demo / 密码 demo123 | 进入查询域 | ✅ 通过 |
| 错误密码 | 正确用户名 + 错误密码 | 显示"用户名或密码错误" | ✅ 通过 |
| 刷新页面 | 登录后刷新 | 跳转登录页（token 在内存中丢失） | ✅ 通过 |
| 退出登录 | 点击退出 | 调用 logout API，清 store，跳转登录 | ✅ 通过 |
| localStorage 残留 | 登录后 DevTools 查看 | Application > Storage 无 token | ✅ 无残留 |
| 普通用户查询 | demo 用户提交查询 | Network 响应中无 sql 字段 | ✅ 通过 |
| admin 用户查询 | admin 用户提交查询 | Network 响应包含 sql 字段，Collapse 可见 | ✅ 通过 |
| SQL Guard 禁词 | 输入含"DROP TABLE"问题 | 返回 422 SQL_GUARD_REJECTED | ✅ 通过 |
| SQL Guard 中文 | 输入含"清空所有记录" | 返回 422 SQL_GUARD_REJECTED | ✅ 通过 |
| 历史页反馈 | 点击好评/差评 | 提交成功，列表显示反馈状态 | ✅ 通过 |
| 组织树选中 | 点击子部门节点 | 右侧显示对应详情 | ✅ 通过 |
| 组织树操作 | 选中节点 | 节点旁显示编辑/删除按钮 | ✅ 通过 |
| 查询空状态 | 未提交查询时 | 显示示例问题标签 | ✅ 通过 |

## 遗留问题（本轮不处理，待 M9 联调时跟进）
- `dist/mockServiceWorker.js` 进入生产产物（需部署时手动删除，已在 README 注明）
- Design Token 仍有约 50 处硬编码颜色（不影响功能，后续统一重构）
- 管理域分栏页面响应式（仍为固定 span，企业内网宽屏场景可接受）

---

## 最终收尾补丁（2026-04-10）

### 修复背景
Codex 终验检查（M3-Hotfix-R2 第三轮审计）发现以下遗留问题，本次一并清零。

### 修复内容
- **FIX-1**：升级 axios 1.14.0 → 1.15.x，消除 SSRF critical 漏洞
  - 升级后 npm audit --audit-level=moderate 结果：found 0 vulnerabilities
- **FIX-2**：删除 queryApi.ts / auditApi.ts 全部 6 处 as unknown as 双重断言
  - 通过 apiGet/apiPost 辅助函数 + 类型安全实现，TypeScript 编译仍然零错误
- **FIX-3**：将 TOKEN_USER_MAP 抽成共享常量（src/mocks/helpers/authHelpers.ts）
  - 补全 mock-jwt-demo-token → demo 用户的映射
  - 所有 MSW handler 统一从 authHelpers.ts import，消除两套表漂移
- **FIX-4**：修复 QueryPage 示例问题点击与重试逻辑
  - QueryInput 支持受控模式（value/onChange props），点击示例标签后输入框立即更新
  - 重试逻辑使用当前选中的组织 ID（useOrgStore），不再硬编码 org-root
- **FIX-5/6**：更新 STATUS.md 和本文档至准确最终状态

### 最终验收测试结果
| 测试项 | 命令/方法 | 结果 |
|--------|-----------|------|
| TypeScript 编译 | npx tsc --noEmit | ✅ 0 错误 |
| TypeScript 严格模式 | npx tsc --noEmit --strict | ✅ 0 错误 |
| 生产构建 | npm run build | ✅ 成功 |
| 依赖安全审计 | npm audit --audit-level=moderate | ✅ 0 漏洞 |
| localStorage 清零 | grep -rn "localStorage" src/ | ✅ 0 结果 |
| as unknown as 清零 | grep -rn "as unknown as" src/ | ✅ 0 结果 |
| demo token 映射 | demo 用户查询，响应无 sql 字段 | ✅ 正确识别 |
| token 定义集中 | grep -rn "mock-jwt" src/mocks/handlers/ | 仅凭证映射（必要），非重复定义 |
| 示例问题受控更新 | 点击示例标签，输入框文本更新 | ✅ 正常 |
| 重试使用当前组织 | 查询出错后点重试 | ✅ 使用当前选中组织 |

### M3 阶段归档声明
前端 M3 阶段（M0-M3 + M3-Hotfix + M3-Hotfix-R2 + 最终收尾）全部完成。
**等待人工审批放行 M4。**
