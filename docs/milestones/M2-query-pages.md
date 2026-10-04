# M2 — 查询域前端页面

## 基本信息
- 开始日期：2026-04-09
- 完成日期：2026-04-09
- 状态：✅ 已完成

## 交付物

### Mock 数据层
- `src/mocks/queryData.ts` — 查询 Mock（5场景：bar/line/pie/table/warning）
- `src/mocks/index.ts` — 添加 queryData 导出
- `src/services/queryApi.ts` — 接入 Mock，与 M1 模式一致

### 查询组件 (`src/components/query/`)
- `QueryInput.tsx` — 自然语言输入 + 快捷问题标签
- `ResultTable.tsx` — 动态表格（分页、排序）
- `ResultChart.tsx` — ECharts 图表（bar/line/pie）
- `QuerySummary.tsx` — 摘要 + 警告提示
- `SqlPreview.tsx` — SQL 预览（仅管理员可见）
- `ConfidenceBadge.tsx` — 置信度标签（高/中/低）
- `LoadingSkeleton.tsx` — 加载骨架屏

### 查询页面 (`src/pages/query/`)
- `QueryPage.tsx` — 主查询页面（空状态/加载中/结果展示）
- `HistoryPage.tsx` — 历史记录（详情抽屉/复用/反馈）
- `FavoritesPage.tsx` — 收藏占位页面

### Store 改造
- `useQueryStore.ts` — 增强 submitQuery 逻辑、错误处理

### URL 参数支持
- `QueryPage.tsx` — 支持 `?q=` 参数预填问题

## 测试记录

| 测试项 | 方法 | 结果 |
|--------|------|------|
| TypeScript 编译 | npx tsc --noEmit | ✅ 零错误 |
| 生产构建 | npm run build | ✅ 成功 |
| QueryInput 渲染 | 手动测试 | ✅ 输入框正常 |
| QueryInput 快捷标签 | 手动测试 | ✅ 点击填充正常 |
| QueryPage 空状态 | 手动测试 | ✅ 提示信息显示正常 |
| QueryPage 加载状态 | 手动测试 | ✅ 骨架屏正常 |
| QueryPage 结果展示 | 手动测试 | ✅ 表格/图表正常 |
| SqlPreview 管理员可见 | 手动测试 | ✅ SQL 折叠面板正常 |
| ConfidenceBadge 颜色 | 手动测试 | ✅ 高/中/低颜色正确 |
| HistoryPage 列表 | 手动测试 | ✅ 历史记录显示正常 |
| HistoryPage 详情抽屉 | 手动测试 | ✅ 详情展示正常 |
| HistoryPage 复用按钮 | 手动测试 | ✅ 跳转并填充问题 |
| HistoryPage 反馈按钮 | 手动测试 | ✅ 反馈提交正常 |
| URL 参数预填 | 手动测试 | ✅ `?q=` 参数正常 |

## 技术说明

- 所有页面使用函数式组件 + Hooks
- 组件 Props 使用 `src/types/` 中定义的 TypeScript 类型
- Mock 数据与 types 定义完全匹配
- SqlPreview 通过 `useAuthStore().isAdmin()` 控制显示
- 置信度阈值：≥0.8 高(绿)、≥0.6 中(橙)、<0.6 低(红)
- ECharts 配置响应式，支持 bar/line/pie 三种图表类型

## 遗留问题

（暂无）
