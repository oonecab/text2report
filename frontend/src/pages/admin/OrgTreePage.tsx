import React, { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Checkbox,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tree,
  message,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import { ReloadOutlined, UserAddOutlined } from '@ant-design/icons';
import { orgApi } from '@/services/orgApi';
import { userApi } from '@/services/userApi';
import type { OrgNode } from '@/types';
import type { OrgUserVO, SysUser, UserOrgGrantRequest } from '@/types/user';
import { presentRequestError } from '@/utils/apiError';

const toTreeData = (nodes: OrgNode[]): DataNode[] =>
  nodes.map((node) => ({
    key: node.id,
    title: node.name,
    children: node.children ? toTreeData(node.children) : undefined,
  }));

const countNodes = (nodes: OrgNode[]): number =>
  nodes.reduce((total, node) => total + 1 + countNodes(node.children ?? []), 0);

const OrgTreePage: React.FC = () => {
  const [orgTree, setOrgTree] = useState<OrgNode[]>([]);
  const [selectedOrgId, setSelectedOrgId] = useState<string | number | null>(null);
  const [orgUsers, setOrgUsers] = useState<OrgUserVO[]>([]);
  const [loadingTree, setLoadingTree] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [candidateUsers, setCandidateUsers] = useState<SysUser[]>([]);
  const [searchingUsers, setSearchingUsers] = useState(false);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [form] = Form.useForm<UserOrgGrantRequest & { username?: string }>();

  const selectedOrg = useMemo(() => {
    const walk = (nodes: OrgNode[]): OrgNode | null => {
      for (const node of nodes) {
        if (node.id === selectedOrgId) return node;
        const found = walk(node.children ?? []);
        if (found) return found;
      }
      return null;
    };
    return walk(orgTree);
  }, [orgTree, selectedOrgId]);

  const loadTree = async () => {
    setLoadingTree(true);
    try {
      const data = await orgApi.getMyTree();
      setOrgTree(data);
      if (!selectedOrgId && data.length > 0) {
        setSelectedOrgId(data[0].id);
      }
    } catch (error) {
      presentRequestError(error, message.error, '加载组织树失败');
    } finally {
      setLoadingTree(false);
    }
  };

  const loadUsersByOrg = async (orgId: string | number) => {
    setLoadingUsers(true);
    try {
      const data = await orgApi.getUsersByOrg(orgId);
      setOrgUsers(data);
    } catch (error) {
      presentRequestError(error, message.error, '加载组织账号失败');
    } finally {
      setLoadingUsers(false);
    }
  };

  useEffect(() => {
    void loadTree();
  }, []);

  useEffect(() => {
    if (selectedOrgId != null) {
      void loadUsersByOrg(selectedOrgId);
    }
  }, [selectedOrgId]);

  const handleSync = async () => {
    setSyncing(true);
    try {
      const result = await orgApi.sync();
      message.success(`同步完成：新增 ${result.inserted} 条，更新 ${result.updated} 条，停用 ${result.deactivated} 条`);
      await loadTree();
      if (selectedOrgId != null) {
        await loadUsersByOrg(selectedOrgId);
      }
    } catch (error) {
      presentRequestError(error, message.error, '组织同步失败');
    } finally {
      setSyncing(false);
    }
  };

  const handleSearchUsers = async () => {
    setSearchingUsers(true);
    try {
      const result = await userApi.list({ page: 1, size: 20, keyword: searchKeyword || undefined });
      setCandidateUsers(result.list);
    } catch (error) {
      presentRequestError(error, message.error, '搜索用户失败');
    } finally {
      setSearchingUsers(false);
    }
  };

  const handleAddUser = async () => {
    if (selectedOrgId == null) return;
    try {
      const values = await form.validateFields();
      const selectedUser = candidateUsers.find((item) => item.id === values.userId);
      const payload: UserOrgGrantRequest = {
        userId: values.userId,
        orgId: Number(selectedOrgId),
        scope: values.scope,
        isPrimary: values.isPrimary,
      };
      await orgApi.addUserToOrg(selectedOrgId, {
        ...payload,
        username: selectedUser?.username,
        displayName: selectedUser?.displayName,
        role: selectedUser?.role,
      } as UserOrgGrantRequest & Record<string, unknown>);
      message.success('账号已加入组织');
      setAddModalOpen(false);
      form.resetFields();
      setCandidateUsers([]);
      setSearchKeyword('');
      await loadUsersByOrg(selectedOrgId);
    } catch (error) {
      presentRequestError(error, message.error, '添加账号失败');
    }
  };

  const handleRemoveUser = async (userId: number) => {
    if (selectedOrgId == null) return;
    try {
      await orgApi.removeUserFromOrg(selectedOrgId, userId);
      message.success('账号已移除');
      await loadUsersByOrg(selectedOrgId);
    } catch (error) {
      presentRequestError(error, message.error, '移除账号失败');
    }
  };

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <h2 style={{ marginBottom: 4 }}>组织人员管理</h2>
        <div style={{ color: '#666' }}>左侧查看权限范围内的组织树，右侧维护组织下已授权的系统账号。</div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '360px 1fr', gap: 16 }}>
        <Card
          title="组织树"
          loading={loadingTree}
          extra={
            <Space>
              <span style={{ fontSize: 12, color: '#999' }}>共 {countNodes(orgTree)} 个节点</span>
              <Button icon={<ReloadOutlined />} loading={syncing} onClick={() => void handleSync()}>
                同步组织
              </Button>
            </Space>
          }
        >
          <Tree
            blockNode
            showLine
            selectedKeys={selectedOrgId != null ? [selectedOrgId] : []}
            treeData={toTreeData(orgTree)}
            onSelect={(keys) => setSelectedOrgId((keys[0] as string | number | undefined) ?? null)}
          />
        </Card>

        <Card
          title={selectedOrg ? `${selectedOrg.name} 的系统账号` : '组织账号'}
          extra={
            <Button
              type="primary"
              icon={<UserAddOutlined />}
              disabled={selectedOrgId == null}
              onClick={() => {
                form.resetFields();
                setCandidateUsers([]);
                setSearchKeyword('');
                setAddModalOpen(true);
              }}
            >
              添加账号
            </Button>
          }
        >
          {selectedOrgId == null ? (
            <Empty description="请选择左侧组织节点" />
          ) : (
            <Table
              rowKey="userId"
              loading={loadingUsers}
              dataSource={orgUsers}
              pagination={false}
              columns={[
                { title: '用户名', dataIndex: 'username', key: 'username', width: 160 },
                { title: '显示名', dataIndex: 'displayName', key: 'displayName', width: 160 },
                {
                  title: '角色',
                  dataIndex: 'role',
                  key: 'role',
                  width: 120,
                  render: (value: string) => <Tag>{value}</Tag>,
                },
                {
                  title: 'Scope',
                  dataIndex: 'scope',
                  key: 'scope',
                  width: 120,
                  render: (value: string) => <Tag color="blue">{value}</Tag>,
                },
                {
                  title: '默认组织',
                  dataIndex: 'isPrimary',
                  key: 'isPrimary',
                  width: 120,
                  render: (value: boolean) => <Tag color={value ? 'green' : 'default'}>{value ? '是' : '否'}</Tag>,
                },
                {
                  title: '操作',
                  key: 'actions',
                  render: (_: unknown, record: OrgUserVO) => (
                    <Button type="link" danger onClick={() => void handleRemoveUser(record.userId)}>
                      移除
                    </Button>
                  ),
                },
              ]}
            />
          )}
        </Card>
      </div>

      <Modal
        title={selectedOrg ? `添加账号到 ${selectedOrg.name}` : '添加账号'}
        open={addModalOpen}
        onOk={() => void handleAddUser()}
        onCancel={() => setAddModalOpen(false)}
        width={640}
      >
        <Space.Compact style={{ width: '100%', marginBottom: 16 }}>
          <Input
            value={searchKeyword}
            onChange={(e) => setSearchKeyword(e.target.value)}
            onPressEnter={() => void handleSearchUsers()}
            placeholder="输入用户名或显示名搜索"
          />
          <Button loading={searchingUsers} onClick={() => void handleSearchUsers()}>搜索</Button>
        </Space.Compact>

        <Form form={form} layout="vertical">
          <Form.Item
            label="选择账号"
            name="userId"
            rules={[{ required: true, message: '请选择账号' }]}
          >
            <Select
              showSearch
              options={candidateUsers.map((item) => ({
                label: `${item.username} / ${item.displayName ?? item.username} (${item.role})`,
                value: item.id,
              }))}
              placeholder="先搜索，再选择账号"
            />
          </Form.Item>
          <Form.Item
            label="权限范围"
            name="scope"
            initialValue="SELF"
            rules={[{ required: true, message: '请选择权限范围' }]}
          >
            <Select
              options={[
                { label: 'SELF', value: 'SELF' },
                { label: 'CHILDREN', value: 'CHILDREN' },
                { label: 'ALL', value: 'ALL' },
              ]}
            />
          </Form.Item>
          <Form.Item name="isPrimary" valuePropName="checked">
            <Checkbox>设为默认组织</Checkbox>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default OrgTreePage;
