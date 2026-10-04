import React, { useEffect, useMemo, useState } from 'react';
import { Typography, Card, Table, Tag, Button, Space, Popconfirm, message, Modal, Form, Select, Input, Tooltip } from 'antd';
import { PlusOutlined, DeleteOutlined, ArrowRightOutlined } from '@ant-design/icons';
import { AdminTableFilterBar } from '@/components/admin';
import { fetchJoins, createJoin, deleteJoin, fetchTables, fetchColumns } from '@/services/metadataApi';
import type { JoinEdge, MetaTable, MetaColumn } from '@/types';
import type { AdminTableFilterField, AdminTableFilterValues } from '@/components/admin';
import { presentRequestError } from '@/utils/apiError';
import { buildCompactTablePagination } from '@/utils/tablePagination';

const { Title, Paragraph, Text } = Typography;

const JoinEdgePage: React.FC = () => {
  const [joins, setJoins] = useState<JoinEdge[]>([]);
  const [tables, setTables] = useState<MetaTable[]>([]);
  const [columns, setColumns] = useState<Record<number, MetaColumn[]>>({});
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [formLoading, setFormLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10 });
  const [filters, setFilters] = useState<AdminTableFilterValues>({});
  const [form] = Form.useForm();
  const selectedFromTable = Form.useWatch('fromTable', form);
  const selectedToTable = Form.useWatch('toTable', form);

  const isDuplicateJoin = (candidate: Partial<JoinEdge>) => {
    return joins.some((item) => {
      const sameDirection = item.fromTable === candidate.fromTable
        && item.fromColumn === candidate.fromColumn
        && item.toTable === candidate.toTable
        && item.toColumn === candidate.toColumn;
      const reverseDirection = item.fromTable === candidate.toTable
        && item.fromColumn === candidate.toColumn
        && item.toTable === candidate.fromTable
        && item.toColumn === candidate.fromColumn;
      return sameDirection || reverseDirection;
    });
  };

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoading(true);
    try {
      const [joinsData, tablesData] = await Promise.all([
        fetchJoins(),
        fetchTables(),
      ]);
      setJoins(joinsData);
      setTables(tablesData);

      // Pre-fetch columns for all tables
      const columnPromises = tablesData.map(async (t) => {
        const cols = await fetchColumns(t.id);
        return [t.id, cols] as [number, MetaColumn[]];
      });
      const columnResults = await Promise.all(columnPromises);
      const columnMap: Record<number, MetaColumn[]> = {};
      for (const [tableId, cols] of columnResults) {
        columnMap[tableId] = cols;
      }
      setColumns(columnMap);
    } catch (error) {
      presentRequestError(error, message.error, '加载数据失败');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      if (isDuplicateJoin(values)) {
        message.warning('JOIN 关系已存在，无需重复新增');
        return;
      }
      setFormLoading(true);
      await createJoin(values);
      message.success('创建成功');
      setModalVisible(false);
      form.resetFields();
      await loadData();
    } catch (error) {
      presentRequestError(error, message.error, '创建失败');
    } finally {
      setFormLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteJoin(id);
      message.success('删除成功');
      await loadData();
    } catch (error) {
      presentRequestError(error, message.error, '删除失败');
    }
  };

  const getJoinTypeColor = (type: string) => {
    switch (type.toUpperCase()) {
      case 'LEFT': return 'blue';
      case 'INNER': return 'green';
      case 'RIGHT': return 'orange';
      default: return 'default';
    }
  };

  const tableOptions = tables.map((t) => ({
    value: t.tableName,
    label: `${t.tableName}${t.label ? ` (${t.label})` : ''}`,
  }));

  const getColumnOptions = (tableName: string) => {
    const table = tables.find((t) => t.tableName === tableName);
    if (!table || !columns[table.id]) return [];
    return columns[table.id].map((c) => ({
      value: c.columnName,
      label: c.columnName,
    }));
  };

  const filterFields: AdminTableFilterField[] = [
    {
      name: 'keyword',
      label: '关键词',
      placeholder: '表名、字段名或说明',
      width: 240,
    },
    {
      name: 'joinType',
      label: 'JOIN 类型',
      type: 'select',
      placeholder: '全部',
      options: [
        { label: 'LEFT JOIN', value: 'LEFT' },
        { label: 'INNER JOIN', value: 'INNER' },
        { label: 'RIGHT JOIN', value: 'RIGHT' },
      ],
      width: 140,
    },
  ];

  const filteredJoins = useMemo(() => {
    const keyword = String(filters.keyword ?? '').toLowerCase();
    const joinType = String(filters.joinType ?? '');

    return joins.filter((item) => {
      const matchesKeyword = !keyword || [
        item.fromTable,
        item.fromColumn,
        item.toTable,
        item.toColumn,
        item.description,
      ].some((value) => String(value ?? '').toLowerCase().includes(keyword));
      const matchesJoinType = !joinType || item.joinType?.toUpperCase() === joinType;

      return matchesKeyword && matchesJoinType;
    });
  }, [filters.keyword, filters.joinType, joins]);

  useEffect(() => {
    const maxPage = Math.max(1, Math.ceil(filteredJoins.length / pagination.pageSize));
    if (pagination.current > maxPage) {
      setPagination((prev) => ({ ...prev, current: maxPage }));
    }
  }, [filteredJoins.length, pagination.current, pagination.pageSize]);

  const columns_def = [
    {
      title: '来源表.字段',
      key: 'from',
      render: (_: unknown, record: JoinEdge) => (
        <Space>
          <Tag>{record.fromTable}</Tag>
          <ArrowRightOutlined style={{ color: '#999' }} />
          <Text code style={{ fontSize: 12 }}>{record.fromColumn}</Text>
        </Space>
      ),
    },
    {
      title: 'JOIN 类型',
      dataIndex: 'joinType',
      key: 'joinType',
      width: 100,
      align: 'center' as const,
      render: (type: string) => (
        <Tag color={getJoinTypeColor(type)}>{type}</Tag>
      ),
    },
    {
      title: '目标表.字段',
      key: 'to',
      render: (_: unknown, record: JoinEdge) => (
        <Space>
          <Tag>{record.toTable}</Tag>
          <ArrowRightOutlined style={{ color: '#999' }} />
          <Text code style={{ fontSize: 12 }}>{record.toColumn}</Text>
        </Space>
      ),
    },
    {
      title: '说明',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (text: string) => text ? <Tooltip title={text}><Text type="secondary">{text}</Text></Tooltip> : '-',
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      align: 'center' as const,
      render: (_: unknown, record: JoinEdge) => (
        <Popconfirm
          title="确认删除"
          description="确定要删除这条 JOIN 关系吗？"
          onConfirm={() => handleDelete(record.id)}
          okText="删除"
          cancelText="取消"
        >
          <Button type="text" size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ];

  const pagedJoins = filteredJoins.slice(
    (pagination.current - 1) * pagination.pageSize,
    pagination.current * pagination.pageSize
  );

  const tablePagination = useMemo(() => buildCompactTablePagination({
    current: pagination.current,
    pageSize: pagination.pageSize,
    total: filteredJoins.length,
    showTotal: (total: number) => `共 ${total} 条`,
    onChange: (current: number, pageSize: number) => {
      setPagination({
        current,
        pageSize,
      });
    },
  }), [filteredJoins.length, pagination.current, pagination.pageSize]);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Title level={4} style={{ marginBottom: 4 }}>JOIN 关系管理</Title>
          <Paragraph type="secondary" style={{ margin: 0 }}>
            配置表之间的 JOIN 关系和连接条件，供 SQL 生成时参考。
          </Paragraph>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setModalVisible(true)}
        >
          新增关系
        </Button>
      </div>

      <Card>
        <AdminTableFilterBar
          fields={filterFields}
          values={filters}
          onSearch={(values) => {
            setFilters(values);
            setPagination((prev) => ({ ...prev, current: 1 }));
          }}
          onReset={() => {
            setFilters({});
            setPagination((prev) => ({ ...prev, current: 1 }));
          }}
        />
        <Table
          className="data-table data-table__compact-pagination"
          columns={columns_def}
          dataSource={pagedJoins}
          rowKey="id"
          loading={loading}
          pagination={tablePagination}
          size="middle"
          locale={{ emptyText: '暂无数据' }}
        />
      </Card>

      <Modal
        title="新增 JOIN 关系"
        open={modalVisible}
        onOk={handleCreate}
        onCancel={() => {
          setModalVisible(false);
          form.resetFields();
        }}
        okText="创建"
        cancelText="取消"
        confirmLoading={formLoading}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item
              name="fromTable"
              label="来源表"
              rules={[{ required: true, message: '请选择来源表' }]}
              style={{ flex: 1 }}
            >
              <Select
                options={tableOptions}
                placeholder="选择来源表"
                onChange={() => form.setFieldValue('fromColumn', undefined)}
              />
            </Form.Item>
            <Form.Item
              name="fromColumn"
              label="来源字段"
              rules={[{ required: true, message: '请选择来源字段' }]}
              style={{ flex: 1 }}
            >
              <Select
                placeholder="先选择来源表"
                options={getColumnOptions(selectedFromTable)}
                disabled={!selectedFromTable}
              />
            </Form.Item>
          </div>

          <Form.Item
            name="joinType"
            label="JOIN 类型"
            rules={[{ required: true, message: '请选择 JOIN 类型' }]}
            initialValue="LEFT"
          >
            <Select
              options={[
                { value: 'LEFT', label: 'LEFT JOIN' },
                { value: 'INNER', label: 'INNER JOIN' },
                { value: 'RIGHT', label: 'RIGHT JOIN' },
              ]}
            />
          </Form.Item>

          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item
              name="toTable"
              label="目标表"
              rules={[{ required: true, message: '请选择目标表' }]}
              style={{ flex: 1 }}
            >
              <Select
                options={tableOptions}
                placeholder="选择目标表"
                onChange={() => form.setFieldValue('toColumn', undefined)}
              />
            </Form.Item>
            <Form.Item
              name="toColumn"
              label="目标字段"
              rules={[{ required: true, message: '请选择目标字段' }]}
              style={{ flex: 1 }}
            >
              <Select
                placeholder="先选择目标表"
                options={getColumnOptions(selectedToTable)}
                disabled={!selectedToTable}
              />
            </Form.Item>
          </div>

          <Form.Item name="description" label="说明">
            <Input.TextArea rows={2} placeholder="可选，说明这条 JOIN 关系的业务含义" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default JoinEdgePage;
