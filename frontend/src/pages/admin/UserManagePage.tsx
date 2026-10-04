import React, { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Col,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tag,
  message,
} from 'antd';
import { EditOutlined, LockOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { userApi } from '@/services/userApi';
import { useAuthStore } from '@/store/useAuthStore';
import type { SysUser, UserCreateRequest, UserUpdateRequest } from '@/types/user';
import { presentRequestError } from '@/utils/apiError';
import { buildCompactTablePagination } from '@/utils/tablePagination';

type StatusFilter = 'ALL' | 'ENABLED' | 'DISABLED';

const roleColorMap: Record<SysUser['role'], string> = {
  ADMIN: 'red',
  MANAGER: 'orange',
  USER: 'blue',
};

const UserManagePage: React.FC = () => {
  const { role: currentRole } = useAuthStore();
  const [users, setUsers] = useState<SysUser[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [passwordModalOpen, setPasswordModalOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<SysUser | null>(null);
  const [passwordTarget, setPasswordTarget] = useState<SysUser | null>(null);
  const [filters, setFilters] = useState<{ role?: SysUser['role']; status: StatusFilter; keyword: string }>({
    role: undefined,
    status: 'ALL',
    keyword: '',
  });
  const [searchKeyword, setSearchKeyword] = useState('');
  const [form] = Form.useForm<UserCreateRequest & { confirmPassword: string }>();
  const [passwordForm] = Form.useForm<{ newPassword: string; confirmPassword: string }>();

  const roleOptions = useMemo(() => {
    if (currentRole === 'ADMIN') {
      return [
        { label: 'ADMIN', value: 'ADMIN' },
        { label: 'MANAGER', value: 'MANAGER' },
        { label: 'USER', value: 'USER' },
      ];
    }
    return [{ label: 'USER', value: 'USER' }];
  }, [currentRole]);

  const loadUsers = async (targetPage = pagination.current, targetPageSize = pagination.pageSize) => {
    setLoading(true);
    try {
      const data = await userApi.list({
        page: targetPage,
        size: targetPageSize,
        role: filters.role,
        isActive: filters.status === 'ALL' ? undefined : filters.status === 'ENABLED',
        keyword: filters.keyword || undefined,
      });
      setUsers(data.list);
      setPagination({
        current: data.page ?? targetPage,
        pageSize: data.pageSize ?? targetPageSize,
        total: data.total ?? data.list.length,
      });
    } catch (error) {
      presentRequestError(error, message.error, '加载用户列表失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    // Keep this dependency list aligned with the filter fields read inside loadUsers.
    // If loadUsers starts reading a new filter field, add it here as well.
    void loadUsers(1, pagination.pageSize);
  }, [filters.role, filters.status, filters.keyword]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const duplicated = !editingUser && users.some((item) => item.username === values.username);
      if (duplicated) {
        message.warning('用户名已存在，请更换后重试');
        return;
      }

      if (editingUser) {
        const payload: UserUpdateRequest = {
          displayName: values.displayName,
          role: values.role,
          email: values.email,
          phone: values.phone,
        };
        await userApi.update(editingUser.id, payload);
        message.success('用户信息已更新');
      } else {
        const payload: UserCreateRequest = {
          username: values.username,
          password: values.password,
          displayName: values.displayName,
          role: values.role,
          email: values.email,
          phone: values.phone,
        };
        await userApi.create(payload);
        message.success('账号创建成功');
      }

      setDrawerOpen(false);
      setEditingUser(null);
      form.resetFields();
      await loadUsers(editingUser ? pagination.current : 1, pagination.pageSize);
    } catch (error) {
      presentRequestError(error, message.error, editingUser ? '更新用户失败' : '创建用户失败');
    }
  };

  const handleToggleStatus = async (record: SysUser) => {
    try {
      await userApi.updateStatus(record.id, !record.isActive);
      message.success(record.isActive ? '账号已禁用' : '账号已启用');
      await loadUsers(pagination.current, pagination.pageSize);
    } catch (error) {
      presentRequestError(error, message.error, '状态更新失败');
    }
  };

  const handleResetPassword = async () => {
    if (!passwordTarget) return;
    try {
      const values = await passwordForm.validateFields();
      await userApi.changePassword(passwordTarget.id, values.newPassword);
      message.success('密码已重置');
      setPasswordModalOpen(false);
      setPasswordTarget(null);
      passwordForm.resetFields();
    } catch (error) {
      presentRequestError(error, message.error, '密码重置失败');
    }
  };

  const columns = [
    {
      title: '用户名',
      dataIndex: 'username',
      key: 'username',
      width: 140,
    },
    {
      title: '显示名',
      dataIndex: 'displayName',
      key: 'displayName',
      width: 140,
    },
    {
      title: '角色',
      dataIndex: 'role',
      key: 'role',
      width: 120,
      render: (value: SysUser['role']) => <Tag color={roleColorMap[value]}>{value}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'isActive',
      key: 'isActive',
      width: 120,
      render: (value: boolean) => <Tag color={value ? 'green' : 'default'}>{value ? '启用' : '禁用'}</Tag>,
    },
    {
      title: '最后登录时间',
      dataIndex: 'lastLoginAt',
      key: 'lastLoginAt',
      render: (value?: string) => value ? value.replace('T', ' ').slice(0, 19) : '-',
    },
    {
      title: '操作',
      key: 'actions',
      width: 220,
      render: (_: unknown, record: SysUser) => (
        <Space>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => {
              setEditingUser(record);
              form.setFieldsValue({
                username: record.username,
                displayName: record.displayName,
                role: record.role,
                email: record.email,
                phone: record.phone,
              });
              setDrawerOpen(true);
            }}
          >
            编辑
          </Button>
          <Popconfirm
            title={`确认${record.isActive ? '禁用' : '启用'}该账号？`}
            onConfirm={() => handleToggleStatus(record)}
            okText="确认"
            cancelText="取消"
          >
            <Button type="link">{record.isActive ? '禁用' : '启用'}</Button>
          </Popconfirm>
          <Button
            type="link"
            icon={<LockOutlined />}
            onClick={() => {
              setPasswordTarget(record);
              passwordForm.resetFields();
              setPasswordModalOpen(true);
            }}
          >
            重置密码
          </Button>
        </Space>
      ),
    },
  ];

  const userPagination = useMemo(() => buildCompactTablePagination({
    current: pagination.current,
    pageSize: pagination.pageSize,
    total: pagination.total,
    pageSizeOptions: ['10', '20', '50'],
    showTotal: (total: number) => `共 ${total} 条`,
    onChange: (targetPage: number, targetPageSize: number) => {
      void loadUsers(
        targetPageSize !== pagination.pageSize ? 1 : targetPage,
        targetPageSize,
      );
    },
  }), [pagination.current, pagination.pageSize, pagination.total, filters.role, filters.status, filters.keyword]);

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <h2 style={{ marginBottom: 4 }}>账号管理</h2>
        <div style={{ color: '#666' }}>按角色和状态管理系统账号，并维护基础资料与密码。</div>
      </div>

      <Card>
        <Row gutter={12} style={{ marginBottom: 16 }}>
          <Col span={5}>
            <Select
              allowClear
              placeholder="角色筛选"
              value={filters.role}
              onChange={(value) => setFilters((prev) => ({ ...prev, role: value }))}
              options={[
                { label: 'ADMIN', value: 'ADMIN' },
                { label: 'MANAGER', value: 'MANAGER' },
                { label: 'USER', value: 'USER' },
              ]}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={5}>
            <Select
              value={filters.status}
              onChange={(value) => setFilters((prev) => ({ ...prev, status: value }))}
              options={[
                { label: '全部状态', value: 'ALL' },
                { label: '启用', value: 'ENABLED' },
                { label: '禁用', value: 'DISABLED' },
              ]}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={8}>
            <Input
              placeholder="搜索用户名/显示名"
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              onPressEnter={() => setFilters((prev) => ({ ...prev, keyword: searchKeyword.trim() }))}
              prefix={<SearchOutlined />}
            />
          </Col>
          <Col span={6} style={{ textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setFilters((prev) => ({ ...prev, keyword: searchKeyword.trim() }))}>搜索</Button>
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => {
                  setEditingUser(null);
                  form.resetFields();
                  setDrawerOpen(true);
                }}
              >
                新建账号
              </Button>
            </Space>
          </Col>
        </Row>

        <Table
          className="data-table data-table__compact-pagination"
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={users}
          pagination={userPagination}
        />
      </Card>

      <Drawer
        title={editingUser ? '编辑账号' : '新建账号'}
        open={drawerOpen}
        width={480}
        onClose={() => {
          setDrawerOpen(false);
          setEditingUser(null);
          form.resetFields();
        }}
        extra={<Button type="primary" onClick={() => void handleSubmit()}>保存</Button>}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            label="用户名"
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input disabled={Boolean(editingUser)} />
          </Form.Item>

          {!editingUser && (
            <>
              <Form.Item
                label="密码"
                name="password"
                rules={[
                  { required: true, message: '请输入密码' },
                  { min: 8, message: '密码至少 8 位' },
                ]}
              >
                <Input.Password />
              </Form.Item>
              <Form.Item
                label="确认密码"
                name="confirmPassword"
                dependencies={['password']}
                rules={[
                  { required: true, message: '请再次输入密码' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      if (!value || getFieldValue('password') === value) {
                        return Promise.resolve();
                      }
                      return Promise.reject(new Error('两次输入的密码不一致'));
                    },
                  }),
                ]}
              >
                <Input.Password />
              </Form.Item>
            </>
          )}

          <Form.Item
            label="显示名"
            name="displayName"
            rules={[{ required: true, message: '请输入显示名' }]}
          >
            <Input />
          </Form.Item>

          <Form.Item
            label="角色"
            name="role"
            rules={[{ required: true, message: '请选择角色' }]}
          >
            <Select options={roleOptions} />
          </Form.Item>

          <Form.Item label="邮箱" name="email">
            <Input />
          </Form.Item>

          <Form.Item label="手机" name="phone">
            <Input />
          </Form.Item>
        </Form>
      </Drawer>

      <Modal
        title={`重置密码${passwordTarget ? ` - ${passwordTarget.username}` : ''}`}
        open={passwordModalOpen}
        onOk={() => void handleResetPassword()}
        onCancel={() => {
          setPasswordModalOpen(false);
          setPasswordTarget(null);
          passwordForm.resetFields();
        }}
      >
        <Form form={passwordForm} layout="vertical">
          <Form.Item
            label="新密码"
            name="newPassword"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, message: '密码至少 8 位' },
            ]}
          >
            <Input.Password />
          </Form.Item>
          <Form.Item
            label="确认新密码"
            name="confirmPassword"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default UserManagePage;
