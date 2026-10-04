import React, { useState } from 'react';
import { Layout, Menu, Avatar, Dropdown, Button } from 'antd';
import {
  ApartmentOutlined,
  UploadOutlined,
  TableOutlined,
  BookOutlined,
  ApiOutlined,
  CommentOutlined,
  AuditOutlined,
  UserOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ArrowLeftOutlined,
  DatabaseOutlined,
  ThunderboltOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/useAuthStore';
import { logout as logoutApi } from '@/services/authApi';
import { BrandMark } from '@/components/common/BrandMark';
import InsightBridgeIcon from '@/components/common/InsightBridgeIcon';

const { Header, Sider, Content } = Layout;

export const AdminLayout: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { user, clearAuth, role, isAdmin, isManagerOrAbove } = useAuthStore();

  const menuItems = [
    {
      key: '/admin/org-tree',
      icon: <ApartmentOutlined />,
      label: '组织人员管理',
    },
    isManagerOrAbove()
      ? {
          key: '/admin/users',
          icon: <UserOutlined />,
          label: '账号管理',
        }
      : null,
    isAdmin()
      ? {
          key: '/admin/embeddings',
          icon: <ThunderboltOutlined />,
          label: '向量检索管理',
        }
      : null,
    isAdmin()
      ? {
          key: '/admin/ddl',
          icon: <UploadOutlined />,
          label: 'DDL 上传解析',
        }
      : null,
    isAdmin()
      ? {
          key: '/admin/datasources',
          icon: <DatabaseOutlined />,
          label: '数据源管理',
        }
      : null,
    isAdmin()
      ? {
          key: '/admin/models',
          icon: <RobotOutlined />,
          label: '模型配置中心',
        }
      : null,
    isAdmin()
      ? {
          key: '/admin/tables',
          icon: <TableOutlined />,
          label: '表与字段管理',
        }
      : null,
    isAdmin()
      ? {
          key: '/admin/glossary',
          icon: <BookOutlined />,
          label: '术语词典管理',
        }
      : null,
    isAdmin()
      ? {
          key: '/admin/joins',
          icon: <ApiOutlined />,
          label: 'JOIN 关系管理',
        }
      : null,
    isManagerOrAbove()
      ? {
          key: '/admin/schema-graph',
          icon: <ApartmentOutlined />,
          label: '表关系图',
        }
      : null,
    isAdmin()
      ? {
          key: '/admin/examples',
          icon: <CommentOutlined />,
          label: '问答示例管理',
        }
      : null,
    isManagerOrAbove()
      ? {
          key: '/admin/audit',
          icon: <AuditOutlined />,
          label: '审计日志',
        }
      : null,
  ].filter(Boolean);

  const userMenuItems = [
    {
      key: 'back',
      icon: <ArrowLeftOutlined />,
      label: '返回查询',
    },
    {
      key: 'logout',
      icon: <UserOutlined />,
      label: '退出登录',
    },
  ];

  const handleMenuClick = async (e: { key: string }) => {
    if (e.key === 'back') {
      navigate('/query');
    } else if (e.key === 'logout') {
      try {
        await logoutApi();
      } finally {
        clearAuth();
        navigate('/login');
      }
    } else {
      navigate(e.key);
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        theme="dark"
        style={{
          borderRight: '1px solid #f0f0f0',
        }}
      >
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: collapsed ? 'center' : 'flex-start',
            padding: collapsed ? '0 12px' : '0 16px',
            borderBottom: '1px solid rgba(255, 255, 255, 0.1)',
            transition: 'padding 0.2s ease, justify-content 0.2s ease',
          }}
        >
          <BrandMark
            collapsed={collapsed}
            icon={<InsightBridgeIcon size={20} />}
            title="InsightBridge"
            tone="dark"
          />
        </div>
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
          style={{ borderRight: 0, marginTop: 8 }}
        />
      </Sider>

      <Layout>
        <Header
          style={{
            padding: '0 24px',
            background: '#fff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Button
              type="text"
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed(!collapsed)}
            />
            <span style={{ fontSize: 14, color: '#666' }}>InsightBridge Admin</span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <Dropdown
              menu={{
                items: userMenuItems,
                onClick: handleMenuClick,
              }}
              placement="bottomRight"
            >
              <Avatar
                style={{ cursor: 'pointer', background: '#1677ff' }}
                icon={<UserOutlined />}
              />
            </Dropdown>
            <span style={{ fontSize: 14 }}>
              {user?.displayName || user?.username} {role ? `(${role})` : ''}
            </span>
          </div>
        </Header>

        <Content
          style={{
            margin: 24,
            padding: 24,
            background: '#fff',
            borderRadius: 8,
            minHeight: 280,
          }}
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};
