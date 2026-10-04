# M0 — 项目初始化 & 前端骨架搭建

## 基本信息
- 开始日期：2026-04-08
- 完成日期：2026-04-08
- 状态：✅ 已完成

## 交付物
- 项目根目录结构（docs/ frontend/ backend/ prompts/ sql/）
- 开发记录三层体系（STATUS.md + milestones/ + decisions/）
- 前端 Vite + React + TypeScript 项目
- 完整的 TypeScript 类型定义（6 个类型文件）
- API 服务层封装（5 个 API 模块 + axios 实例）
- Zustand 状态管理（3 个 store）
- 双域路由 + AuthGuard 角色守卫
- QueryLayout + AdminLayout 布局组件
- 10 个占位页面 + LoginPage
- OrgSelector 公共组件
- 工具函数（constants + formatters）
- ADR-001 技术选型决策记录

## 测试记录
| 测试项 | 方法 | 结果 |
|--------|------|------|
| Vite 启动 | npm run dev | ✅ 成功 |
| 路由跳转（查询域） | 手动浏览器测试 | ✅ 成功 |
| 路由跳转（管理域） | 手动浏览器测试 | ✅ 成功 |
| AuthGuard 权限控制 | user 角色访问 /admin | ✅ 重定向回 /query |
| TypeScript 编译 | npx tsc --noEmit | ✅ 零错误 |
| Build | npm run build | ✅ 成功 |

## 技术说明
- Vite 配置了 @ 路径别名和 /api 代理
- LoginPage 为 Demo 阶段简化实现，用户名含 admin 即赋予管理员角色
- 所有 API 函数已定义但后端未就绪，实际调用会 404（M1 起添加 Mock）

## 遗留问题
（暂无）
