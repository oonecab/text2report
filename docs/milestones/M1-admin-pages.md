# M1 — 管理域前端页面

## 基本信息
- 开始日期：2026-04-09
- 完成日期：2026-04-09
- 状态：✅ 已完成

## 交付物

### Mock 数据层
- `src/mocks/orgData.ts` — 组织树 Mock（华夏集团 → 3子公司 → 9部门）
- `src/mocks/metadataData.ts` — 元数据 Mock（3表/19字段、8术语、2JOIN、6示例）
- `src/mocks/index.ts` — 统一导出 + USE_MOCK 开关

### 共享组件 (`src/components/admin/`)
- `ColumnEditor.tsx` — 字段注释可编辑表格，支持行内编辑 DDL/手动标签
- `OrgTreeEditor.tsx` — 组织树编辑器，支持 CRUD 操作
- `OrgNodeDetail.tsx` — 组织节点详情面板
- `DDLEditor.tsx` — DDL 文本输入组件，支持文件上传
- `GlossaryForm.tsx` — 术语编辑表单（Drawer）

### 管理页面 (`src/pages/admin/`)
- `OrgTreePage.tsx` — 组织机构管理（左右分栏布局）
- `DDLUploadPage.tsx` — DDL 上传解析（两阶段工作流）
- `TableListPage.tsx` — 表与字段管理（左侧列表 + 右侧字段编辑）
- `GlossaryPage.tsx` — 术语词典管理（Table + Drawer）
- `JoinEdgePage.tsx` — JOIN 关系管理（Table + Modal）
- `ExamplesPage.tsx` — 问答示例管理（Table + Modal + Switch）
- `AuditLogPage.tsx` — 审计日志占位页面

### Services 层改造
- 所有 API 函数支持 USE_MOCK 开关切换 Mock/真实后端
- API 路径严格按 architecture 文档定义

## 测试记录

| 测试项 | 方法 | 结果 |
|--------|------|------|
| TypeScript 编译 | npx tsc --noEmit | ✅ 零错误 |
| 生产构建 | npm run build | ✅ 成功 |
| OrgTreePage 渲染 | 手动测试 | ✅ 树结构正确显示 |
| DDLUploadPage 两阶段流程 | 手动测试 | ✅ 解析预览正常 |
| TableListPage 左右分栏 | 手动测试 | ✅ 列表和详情正常 |
| GlossaryPage CRUD | 手动测试 | ✅ 创建/编辑/删除正常 |
| JoinEdgePage Modal | 手动测试 | ✅ 新增 JOIN 关系正常 |
| ExamplesPage Switch | 手动测试 | ✅ 启用/禁用切换正常 |
| AuditLogPage 占位 | 手动测试 | ✅ 提示信息显示正常 |

## 技术说明

- 所有页面使用函数式组件 + Hooks
- 组件 Props 使用 `src/types/` 中定义的 TypeScript 类型
- Mock 数据与 types 定义完全匹配，后端就绪后只需改 USE_MOCK
- ColumnEditor 支持 UnifiedColumn 类型，兼容 MetaColumn 和 DDLParsedColumn
- AdminLayout 菜单图标与 architecture 文档一致

## 遗留问题

（暂无）
