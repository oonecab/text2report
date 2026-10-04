import React, { useEffect, useState } from 'react';
import { Layout, Menu, Avatar, Dropdown, Button, Tag } from 'antd';
import {
  DashboardOutlined,
  HistoryOutlined,
  StarOutlined,
  UserOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/useAuthStore';
import { useOrgStore } from '@/store/useOrgStore';
import { OrgSelector } from '@/components/common/OrgSelector';
import { logout as logoutApi } from '@/services/authApi';
import { BrandMark } from '@/components/common/BrandMark';
import InsightBridgeIcon from '@/components/common/InsightBridgeIcon';

const { Header, Sider, Content } = Layout;

export const QueryLayout: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { user, clearAuth, isAdmin, isManagerOrAbove } = useAuthStore();
  const { fetchOrgTree } = useOrgStore();

  useEffect(() => {
    fetchOrgTree();
  }, [fetchOrgTree]);

  const menuItems = [
    {
      key: '/query',
      icon: <DashboardOutlined />,
      label: '智能查询',
    },
    {
      key: '/query/history',
      icon: <HistoryOutlined />,
      label: '查询历史',
    },
    {
      key: '/query/favorites',
      icon: <StarOutlined />,
      label: '我的收藏',
    },
  ];

  const userMenuItems = [
    ...(isManagerOrAbove()
      ? [
          {
            key: 'admin',
            icon: <SettingOutlined />,
            label: '切换到管理后台',
          },
        ]
      : []),
    {
      key: 'logout',
      icon: <UserOutlined />,
      label: '退出登录',
    },
  ];

  const handleMenuClick = async (e: { key: string }) => {
    if (e.key === 'admin') {
      navigate('/admin');
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
        theme="light"
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
            borderBottom: '1px solid #f0f0f0',
            transition: 'padding 0.2s ease, justify-content 0.2s ease',
          }}
        >
          <BrandMark
            collapsed={collapsed}
            icon={<InsightBridgeIcon size={20} />}
            title="InsightBridge"
            tone="light"
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
            <OrgSelector />
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            {isManagerOrAbove() && (
              <Tag color="orange">{isAdmin() ? '管理后台' : '子公司管理'}</Tag>
            )}
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
              {user?.displayName || user?.username}
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
