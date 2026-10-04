import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AuthGuard } from './AuthGuard';
import { QueryLayout } from '@/layouts/QueryLayout';
import { AdminLayout } from '@/layouts/AdminLayout';

// 页面组件 - 查询域
import QueryPage from '@/pages/query/QueryPage';
import HistoryPage from '@/pages/query/HistoryPage';
import FavoritesPage from '@/pages/query/FavoritesPage';

// 页面组件 - 管理域
import OrgTreePage from '@/pages/admin/OrgTreePage';
import UserManagePage from '@/pages/admin/UserManagePage';
import EmbeddingManagePage from '@/pages/admin/EmbeddingManagePage';
import DDLUploadPage from '@/pages/admin/DDLUploadPage';
import TableListPage from '@/pages/admin/TableListPage';
import GlossaryPage from '@/pages/admin/GlossaryPage';
import JoinEdgePage from '@/pages/admin/JoinEdgePage';
import SchemaGraphPage from '@/pages/admin/SchemaGraphPage';
import ExamplesPage from '@/pages/admin/ExamplesPage';
import AuditLogPage from '@/pages/admin/AuditLogPage';
import DataSourceListPage from '@/pages/admin/DataSourceListPage';
import ModelConfigPage from '@/pages/admin/ModelConfigPage';

// 登录页
import LoginPage from '@/pages/LoginPage';

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: <Navigate to="/query" replace />,
  },
  {
    element: <AuthGuard />,
    children: [
      {
        element: <QueryLayout />,
        children: [
          {
            path: '/query',
            element: <QueryPage />,
          },
          {
            path: '/query/history',
            element: <HistoryPage />,
          },
          {
            path: '/query/favorites',
            element: <FavoritesPage />,
          },
        ],
      },
    ],
  },
  {
    element: <AuthGuard requireManager />,
    children: [
      {
        element: <AdminLayout />,
        children: [
          {
            path: '/admin',
            element: <Navigate to="/admin/org-tree" replace />,
          },
          {
            path: '/admin/org-tree',
            element: <OrgTreePage />,
          },
          {
            path: '/admin/users',
            element: <UserManagePage />,
          },
          {
            path: '/admin/schema-graph',
            element: <SchemaGraphPage />,
          },
          {
            path: '/admin/audit',
            element: <AuditLogPage />,
          },
          {
            element: <AuthGuard requireAdmin />,
            children: [
              {
                path: '/admin/ddl',
                element: <DDLUploadPage />,
              },
              {
                path: '/admin/tables',
                element: <TableListPage />,
              },
              {
                path: '/admin/glossary',
                element: <GlossaryPage />,
              },
              {
                path: '/admin/joins',
                element: <JoinEdgePage />,
              },
              {
                path: '/admin/examples',
                element: <ExamplesPage />,
              },
              {
                path: '/admin/datasources',
                element: <DataSourceListPage />,
              },
              {
                path: '/admin/models',
                element: <ModelConfigPage />,
              },
              {
                path: '/admin/embeddings',
                element: <EmbeddingManagePage />,
              },
            ],
          },
        ],
      },
    ],
  },
]);
