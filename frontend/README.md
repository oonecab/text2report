# Text2Report 前端

Text2Report 的查询与管理界面，使用 React 19、TypeScript 6、Ant Design 5、Vite 8、React Router 7、Zustand 5 和 ECharts 6。项目介绍、后端初始化和安全约束见 [根目录 README](../README.md)。

## 运行与验证

需要 Node.js 20.19+（20.x）或 22.12+。以下命令在 `frontend/` 目录执行：

```bash
npm ci

# 浏览器内模拟接口，预览页面与交互
VITE_USE_MSW=true npm run dev

# 连接真实后端，需先完成后端初始化
VITE_USE_MSW=false npm run dev

npm test        # Node.js 单元测试
npm run build   # TypeScript 检查与生产构建，输出 dist/
npm run lint    # ESLint 检查
npm run preview # 本地查看构建产物
```

开发页面默认地址为 `http://localhost:3000`。预览模式的模拟账号为 `admin / admin123`、`manager / manager123`、`user / user123`；真实登录账号保存在后端元数据库中。

## 环境与接口

| 变量 | 作用 |
|------|------|
| `VITE_USE_MSW` | `true` 开启开发模式的 MSW；仓库 `.env.development` 默认 `false` |
| `VITE_API_BASE_URL` | Vite 开发代理的后端目标，默认 `http://localhost:8080` |

个人环境覆盖可保存在被 Git 忽略的 `.env.development.local`。`VITE_*` 变量可能进入浏览器，必须只包含可公开信息。MSW 只在开发模式启动。

API 客户端统一使用 `/api`，真实登录由 HttpOnly Cookie 维持，写请求携带 CSRF Token。生产静态部署需要单独配置 `/api` 反向代理和 SPA 路由回退，`vite preview` 只用于本地查看产物。

## 代码入口

| 目录或文件 | 用途 |
|------------|------|
| [src/main.tsx](src/main.tsx) | 应用启动与 MSW 开关 |
| [src/app/routes.tsx](src/app/routes.tsx) | 页面路由与角色访问规则 |
| [src/pages/](src/pages/) | 查询、历史、收藏及管理页面 |
| [src/components/](src/components/) | 图表、表格和公共组件 |
| [src/services/request.ts](src/services/request.ts) / [src/services/](src/services/) | HTTP 客户端与 API 模块 |
| [src/store/](src/store/) | 认证、组织与查询状态 |
| [src/mocks/](src/mocks/) | MSW 模拟接口与演示数据 |
| [vite.config.ts](vite.config.ts) | 开发端口、代理与构建配置 |
