# ADR-001: 前端技术栈选择

## 背景
Text2Report 前端需要支持两个业务域（查询域 + 管理域），需要企业级 UI 组件库、全局状态管理和图表渲染能力。

## 决定
- 构建工具：Vite（启动快，HMR 好，TS 原生支持）
- UI 框架：Ant Design 5.x（企业级组件覆盖全，中文文档完善，ProComponents 提供可编辑表格等高级组件）
- 状态管理：Zustand（极简 API，无 Provider 嵌套，3 个 store 足够，不需要 Redux 的 middleware 生态）
- 图表：ECharts + echarts-for-react（中文支持好，图表类型丰富，社区活跃）
- HTTP：Axios（拦截器机制成熟，便于统一处理鉴权和错误）

## 备选方案
- Redux Toolkit：功能全面但样板代码多，本项目状态结构简单不需要
- AntV/G2：Ant 生态图表库，API 优雅但社区和文档不如 ECharts 成熟
- SWR/React Query：数据缓存好，但本项目查询结果不适合缓存（每次 NL 查询结果不同）

## 日期
2026-04-08
