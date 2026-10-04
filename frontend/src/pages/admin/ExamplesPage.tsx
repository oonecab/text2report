import React, { useEffect, useMemo, useState } from 'react';
import { Typography, Card, Table, Tag, Button, Space, Popconfirm, message, Modal, Form, Input, Select, Switch } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { AdminTableFilterBar } from '@/components/admin';
import SqlCodeBlock from '@/components/common/SqlCodeBlock';
import { fetchExamples, createExample, updateExample, deleteExample } from '@/services/metadataApi';
import { fetchOrgTree } from '@/services/orgApi';
import type { SqlExample, OrgNode } from '@/types';
import type { AdminTableFilterField, AdminTableFilterValues } from '@/components/admin';
import { presentRequestError } from '@/utils/apiError';
import { buildCompactTablePagination } from '@/utils/tablePagination';

const { Title, Paragraph, Text } = Typography;

const ExamplesPage: React.FC = () => {
  const [examples, setExamples] = useState<SqlExample[]>([]);
  const [orgTree, setOrgTree] = useState<OrgNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingExample, setEditingExample] = useState<SqlExample | null>(null);
  const [formLoading, setFormLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10 });
  const [filters, setFilters] = useState<AdminTableFilterValues>({});
  const [form] = Form.useForm();

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoading(true);
    try {
      const [examplesData, orgData] = await Promise.all([
        fetchExamples(),
        fetchOrgTree(),
      ]);
      setExamples(examplesData);
      setOrgTree(orgData);
    } catch (err) {
      presentRequestError(err, message.error, '加载数据失败');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      setFormLoading(true);
      await createExample(values);
      message.success('创建成功');
      message.info('已触发向量化，embedding 将在后台异步更新');
      setModalVisible(false);
      form.resetFields();
      await loadData();
    } catch (err) {
      presentRequestError(err, message.error, '创建失败');
    } finally {
      setFormLoading(false);
    }
  };

  const handleUpdate = async () => {
    if (!editingExample) return;
    try {
      const values = await form.validateFields();
      setFormLoading(true);
      await updateExample(editingExample.id, values);
      message.success('更新成功');
      message.info('已触发向量化，embedding 将在后台异步更新');
      setModalVisible(false);
      setEditingExample(null);
      form.resetFields();
      await loadData();
    } catch (err) {
      presentRequestError(err, message.error, '更新失败');
    } finally {
      setFormLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteExample(id);
      message.success('删除成功');
      await loadData();
    } catch (err) {
      presentRequestError(err, message.error, '删除失败');
    }
  };

  const handleToggleActive = async (id: number, isActive: boolean) => {
    try {
      await updateExample(id, { isActive });
      message.success(isActive ? '已启用' : '已禁用');
      await loadData();
    } catch (err) {
      presentRequestError(err, message.error, '状态更新失败');
    }
  };

  // Flatten org tree for select (aligned with OrgTreeEditor style)
  const flattenOrgTree = (
    nodes: OrgNode[],
    depth: number = 0,
    result: { value: string | number; label: string }[] = []
  ): typeof result => {
    for (const node of nodes) {
      const indent = '　　'.repeat(depth);
      const prefix = depth > 0 ? '└─ ' : '';
      const levelLabel =
        node.level === 1 ? '母公司' :
        node.level === 2 ? '子公司' :
        node.level === 3 ? '部门' :
        node.level && node.level > 3 ? `第${node.level}级` : '未知层级';
      const label = `${indent}${prefix}${node.name} (${levelLabel})`;
      result.push({ value: node.id, label });
      if (node.children && node.children.length > 0) {
        flattenOrgTree(node.children, depth + 1, result);
      }
    }
    return result;
  };

  const orgOptions = flattenOrgTree(orgTree);

  const renderOrgTag = (props: { label: React.ReactNode; value: React.Key; closable: boolean; onClose: (e?: React.MouseEvent<HTMLElement, MouseEvent>) => void }) => {
    const { label, closable, onClose } = props;
    const raw = String(label);
    const name = raw.replace(/^[　\s└─]+/, '').replace(/\s*\(.+\)$/, '');
    return (
      <Tag closable={closable} onClose={onClose} style={{ marginRight: 4 }}>
        {name}
      </Tag>
    );
  };

  const domainOptions = [
    { value: '毛利分析', label: '毛利分析' },
    { value: '收入分析', label: '收入分析' },
    { value: '成本分析', label: '成本分析' },
    { value: '其他', label: '其他' },
  ];

  const filterFields: AdminTableFilterField[] = [
    {
      name: 'keyword',
      label: '关键词',
      placeholder: '问题、SQL 或业务域',
      width: 260,
    },
    {
      name: 'domain',
      label: '业务域',
      type: 'select',
      placeholder: '全部',
      options: domainOptions,
      width: 140,
    },
    {
      name: 'status',
      label: '状态',
      type: 'select',
      placeholder: '全部',
      options: [
        { label: '启用', value: 'active' },
        { label: '禁用', value: 'inactive' },
      ],
      width: 120,
    },
  ];

  const filteredExamples = useMemo(() => {
    const keyword = String(filters.keyword ?? '').toLowerCase();
    const domain = String(filters.domain ?? '');
    const status = String(filters.status ?? '');

    return examples.filter((item) => {
      const matchesKeyword = !keyword || [
        item.question,
        item.sql,
        item.domain,
      ].some((value) => String(value ?? '').toLowerCase().includes(keyword));
      const matchesDomain = !domain || item.domain === domain;
      const matchesStatus =
        !status ||
        (status === 'active' && item.isActive) ||
        (status === 'inactive' && !item.isActive);

      return matchesKeyword && matchesDomain && matchesStatus;
    });
  }, [examples, filters.domain, filters.keyword, filters.status]);

  useEffect(() => {
    const maxPage = Math.max(1, Math.ceil(filteredExamples.length / pagination.pageSize));
    if (pagination.current > maxPage) {
      setPagination((prev) => ({ ...prev, current: maxPage }));
    }
  }, [filteredExamples.length, pagination.current, pagination.pageSize]);

  const columns = [
    {
      title: '问题',
      dataIndex: 'question',
      key: 'question',
      render: (text: string) => <Text strong>{text}</Text>,
    },
    {
      title: 'SQL',
      dataIndex: 'sql',
      key: 'sql',
      width: 250,
      ellipsis: { showTitle: false },
      render: (text: string) => (
        <SqlCodeBlock sql={text} variant="inline" truncateAt={42} showCopy={false} />
      ),
    },
    {
      title: '业务域',
      dataIndex: 'domain',
      key: 'domain',
      width: 100,
      render: (domain: string) => <Tag color="blue">{domain || '其他'}</Tag>,
    },
    {
      title: '适用范围',
      dataIndex: 'orgScope',
      key: 'orgScope',
      width: 120,
      render: (scope: number[] | null) => {
        if (!scope || scope.length === 0) {
          return <Tag color="green">全局</Tag>;
        }
        return <Tag>{scope.length} 个组织</Tag>;
      },
    },
    {
      title: '状态',
      dataIndex: 'isActive',
      key: 'isActive',
      width: 80,
      align: 'center' as const,
      render: (isActive: boolean, record: SqlExample) => (
        <Switch
          checked={isActive}
          onChange={(checked) => handleToggleActive(record.id, checked)}
          size="small"
        />
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: unknown, record: SqlExample) => (
        <Space size="small">
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            onClick={() => {
              setEditingExample(record);
              form.setFieldsValue({
                question: record.question,
                sql: record.sql,
                domain: record.domain,
                orgScope: record.orgScope,
                isActive: record.isActive,
              });
              setModalVisible(true);
            }}
          />
          <Popconfirm
            title="确认删除"
            description="删除后无法恢复，确定要删除吗？"
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

  const pagedExamples = filteredExamples.slice(
    (pagination.current - 1) * pagination.pageSize,
    pagination.current * pagination.pageSize
  );

  const tablePagination = useMemo(() => buildCompactTablePagination({
    current: pagination.current,
    pageSize: pagination.pageSize,
    total: filteredExamples.length,
    showTotal: (total: number) => `共 ${total} 条`,
    onChange: (current: number, pageSize: number) => {
      setPagination({
        current,
        pageSize,
      });
    },
  }), [filteredExamples.length, pagination.current, pagination.pageSize]);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Title level={4} style={{ marginBottom: 4 }}>问答示例管理</Title>
          <Paragraph type="secondary" style={{ margin: 0 }}>
            管理问题与标准 SQL 的示例对，供 LLM 学习参考。
          </Paragraph>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setEditingExample(null);
            form.resetFields();
            setModalVisible(true);
          }}
        >
          新增示例
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
          columns={columns}
          dataSource={pagedExamples}
          rowKey="id"
          loading={loading}
          pagination={tablePagination}
          size="middle"
          locale={{ emptyText: '暂无数据' }}
        />
      </Card>

      <Modal
        title={editingExample ? '编辑示例' : '新增示例'}
        open={modalVisible}
        onOk={editingExample ? handleUpdate : handleCreate}
        onCancel={() => {
          setModalVisible(false);
          setEditingExample(null);
          form.resetFields();
        }}
        okText={editingExample ? '更新' : '创建'}
        cancelText="取消"
        confirmLoading={formLoading}
        width={640}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="question"
            label="问题"
            rules={[{ required: true, message: '请输入问题' }]}
          >
            <Input.TextArea rows={2} placeholder="如：子公司A今年各部门毛利率" />
          </Form.Item>

          <Form.Item
            name="sql"
            label="标准 SQL"
            rules={[{ required: true, message: '请输入 SQL' }]}
          >
            <Input.TextArea rows={4} placeholder="对应的标准 SQL 语句" style={{ fontFamily: 'monospace' }} />
          </Form.Item>

          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item
              name="domain"
              label="业务域"
              style={{ flex: 1 }}
            >
              <Select options={domainOptions} placeholder="选择业务域" />
            </Form.Item>

            <Form.Item
              name="orgScope"
              label="适用范围"
              style={{ flex: 1 }}
            >
              <Select
                options={orgOptions}
                mode="multiple"
                allowClear
                placeholder="留空表示全局"
                tagRender={renderOrgTag}
              />
            </Form.Item>
          </div>

          <Form.Item
            name="isActive"
            label="启用状态"
            valuePropName="checked"
            initialValue={true}
          >
            <Switch checkedChildren="启用" unCheckedChildren="禁用" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ExamplesPage;
