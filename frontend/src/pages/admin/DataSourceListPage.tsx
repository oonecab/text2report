import React, { useEffect, useMemo, useState } from 'react';
import { Typography, Card, Table, Tag, Button, Space, Popconfirm, message, Modal } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { DataSourceForm } from '@/components/admin';
import { fetchDataSources, createDataSource, updateDataSource, deleteDataSource } from '@/services/dataSourceApi';
import type { DataSource } from '@/types';
import { presentRequestError } from '@/utils/apiError';
import { buildCompactTablePagination } from '@/utils/tablePagination';

const { Title, Paragraph, Text } = Typography;

const DataSourceListPage: React.FC = () => {
  const [datasources, setDatasources] = useState<DataSource[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingItem, setEditingItem] = useState<DataSource | null>(null);
  const [formLoading, setFormLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10 });

  useEffect(() => {
    loadData();
  }, []);

  useEffect(() => {
    const maxPage = Math.max(1, Math.ceil(datasources.length / pagination.pageSize));
    if (pagination.current > maxPage) {
      setPagination((prev) => ({ ...prev, current: maxPage }));
    }
  }, [datasources.length, pagination.current, pagination.pageSize]);

  const loadData = async () => {
    setLoading(true);
    try {
      const result = await fetchDataSources();
      setDatasources(result);
    } catch (err) {
      presentRequestError(err, message.error, '加载数据失败');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async (values: Partial<DataSource>) => {
    setFormLoading(true);
    try {
      await createDataSource(values);
      setModalVisible(false);
      message.success('创建成功');
      await loadData();
    } catch (err) {
      presentRequestError(err, message.error, '创建失败');
    } finally {
      setFormLoading(false);
    }
  };

  const handleUpdate = async (values: Partial<DataSource>) => {
    if (!editingItem) return;
    setFormLoading(true);
    try {
      await updateDataSource(editingItem.id, values);
      setModalVisible(false);
      setEditingItem(null);
      message.success('更新成功');
      await loadData();
    } catch (err) {
      presentRequestError(err, message.error, '更新失败');
    } finally {
      setFormLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteDataSource(id);
      message.success('删除成功');
      await loadData();
    } catch (err) {
      presentRequestError(err, message.error, '删除失败');
    }
  };

  const dbTypeColors: Record<string, string> = {
    postgresql: 'green',
    mysql: 'blue',
    sqlserver: 'red',
  };

  const columns = [
    {
      title: '数据源名称',
      dataIndex: 'name',
      key: 'name',
      width: 150,
      render: (text: string, record: DataSource) => (
        <Space>
          <Text strong>{text}</Text>
          {!record.isActive && <Tag color="default">已停用</Tag>}
        </Space>
      ),
    },
    {
      title: '数据库类型',
      dataIndex: 'dbType',
      key: 'dbType',
      width: 120,
      render: (text: string) => (
        <Tag color={dbTypeColors[text] || 'default'}>{text.toUpperCase()}</Tag>
      ),
    },
    {
      title: 'JDBC URL',
      dataIndex: 'jdbcUrl',
      key: 'jdbcUrl',
      width: 250,
      render: (_: unknown, record: DataSource) => (
        <Text type="secondary" style={{ fontSize: 12 }}>{record.jdbcUrl || '-'}</Text>
      ),
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => <Text type="secondary">{text || '-'}</Text>,
    },
    {
      title: '只读',
      dataIndex: 'isReadOnly',
      key: 'isReadOnly',
      width: 80,
      render: (isReadOnly: boolean) => (
        isReadOnly ? <Tag color="cyan">只读</Tag> : <Tag>读写</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'isActive',
      key: 'isActive',
      width: 80,
      render: (isActive: boolean) => (
        isActive ? <Tag color="success">启用</Tag> : <Tag color="default">停用</Tag>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: unknown, record: DataSource) => (
        <Space size="small">
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            onClick={() => {
              setEditingItem(record);
              setModalVisible(true);
            }}
          />
          <Popconfirm
            title="确认删除"
            description="删除后将无法恢复，确定要删除吗？"
            onConfirm={() => handleDelete(record.id)}
            okText="删除"
            cancelText="取消"
          >
            <Button type="text" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const pagedDatasources = datasources.slice(
    (pagination.current - 1) * pagination.pageSize,
    pagination.current * pagination.pageSize
  );

  const tablePagination = useMemo(() => buildCompactTablePagination({
    current: pagination.current,
    pageSize: pagination.pageSize,
    total: datasources.length,
    showTotal: (total: number) => `共 ${total} 条`,
    onChange: (current: number, pageSize: number) => {
      setPagination({
        current,
        pageSize,
      });
    },
  }), [datasources.length, pagination.current, pagination.pageSize]);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Title level={4} style={{ marginBottom: 4 }}>数据源管理</Title>
          <Paragraph type="secondary" style={{ margin: 0 }}>
            管理数据库连接配置，支持 PostgreSQL、MySQL、SQL Server 等数据源。
          </Paragraph>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setEditingItem(null);
            setModalVisible(true);
          }}
        >
          新增数据源
        </Button>
      </div>

      <Card>
        <Table
          className="data-table data-table__compact-pagination"
          columns={columns}
          dataSource={pagedDatasources}
          rowKey="id"
          loading={loading}
          pagination={tablePagination}
          size="middle"
          locale={{ emptyText: '暂无数据' }}
        />
      </Card>

      <Modal
        title={editingItem ? '编辑数据源' : '新增数据源'}
        open={modalVisible}
        onCancel={() => {
          setModalVisible(false);
          setEditingItem(null);
        }}
        footer={null}
        destroyOnClose
      >
        <DataSourceForm
          initialValues={editingItem || undefined}
          onSubmit={editingItem ? handleUpdate : handleCreate}
          onCancel={() => {
            setModalVisible(false);
            setEditingItem(null);
          }}
          loading={formLoading}
        />
      </Modal>
    </div>
  );
};

export default DataSourceListPage;
