import React, { useEffect, useMemo, useState } from 'react';
import { Typography, Card, Table, Tag, Button, Space, Popconfirm, message, Drawer } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { AdminTableFilterBar, GlossaryForm } from '@/components/admin';
import SqlCodeBlock from '@/components/common/SqlCodeBlock';
import { fetchGlossary, createGlossary, updateGlossary, deleteGlossary } from '@/services/metadataApi';
import { fetchOrgTree } from '@/services/orgApi';
import type { Glossary, OrgNode } from '@/types';
import type { AdminTableFilterField, AdminTableFilterValues } from '@/components/admin';
import { presentRequestError } from '@/utils/apiError';
import { buildCompactTablePagination } from '@/utils/tablePagination';

const { Title, Paragraph, Text } = Typography;

const GlossaryPage: React.FC = () => {
  const [glossary, setGlossary] = useState<Glossary[]>([]);
  const [orgTree, setOrgTree] = useState<OrgNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerVisible, setDrawerVisible] = useState(false);
  const [editingItem, setEditingItem] = useState<Glossary | null>(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10 });
  const [filters, setFilters] = useState<AdminTableFilterValues>({});

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoading(true);
    try {
      const [glossaryResult, orgData] = await Promise.all([
        fetchGlossary(),
        fetchOrgTree(),
      ]);
      setGlossary(glossaryResult.list);
      setOrgTree(orgData);
    } catch (err) {
      presentRequestError(err, message.error, '加载数据失败');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async (values: Partial<Glossary>) => {
    try {
      await createGlossary(values);
      message.success('创建成功');
      message.info('已触发向量化，embedding 将在后台异步更新');
      setDrawerVisible(false);
      await loadData();
    } catch (err) {
      presentRequestError(err, message.error, '创建失败');
    }
  };

  const handleUpdate = async (values: Partial<Glossary>) => {
    if (!editingItem) return;
    try {
      await updateGlossary(editingItem.id, values);
      message.success('更新成功');
      message.info('已触发向量化，embedding 将在后台异步更新');
      setDrawerVisible(false);
      setEditingItem(null);
      await loadData();
    } catch (err) {
      presentRequestError(err, message.error, '更新失败');
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteGlossary(id);
      message.success('删除成功');
      await loadData();
    } catch (err) {
      presentRequestError(err, message.error, '删除失败');
    }
  };

  const filterFields: AdminTableFilterField[] = [
    {
      name: 'keyword',
      label: '关键词',
      placeholder: '术语、定义、别名或 SQL',
      width: 240,
    },
    {
      name: 'scopeType',
      label: '适用范围',
      type: 'select',
      placeholder: '全部',
      options: [
        { label: '全局', value: 'global' },
        { label: '指定组织', value: 'scoped' },
      ],
      width: 140,
    },
  ];

  const filteredGlossary = useMemo(() => {
    const keyword = String(filters.keyword ?? '').toLowerCase();
    const scopeType = filters.scopeType;

    return glossary.filter((item) => {
      const matchesKeyword = !keyword || [
        item.term,
        item.definition,
        item.sqlSnippet,
        ...(item.aliases ?? []),
      ].some((value) => String(value ?? '').toLowerCase().includes(keyword));
      const isGlobal = !item.orgScope || item.orgScope.length === 0;
      const matchesScope =
        !scopeType ||
        (scopeType === 'global' && isGlobal) ||
        (scopeType === 'scoped' && !isGlobal);

      return matchesKeyword && matchesScope;
    });
  }, [filters.keyword, filters.scopeType, glossary]);

  useEffect(() => {
    const maxPage = Math.max(1, Math.ceil(filteredGlossary.length / pagination.pageSize));
    if (pagination.current > maxPage) {
      setPagination((prev) => ({ ...prev, current: maxPage }));
    }
  }, [filteredGlossary.length, pagination.current, pagination.pageSize]);

  const columns = [
    {
      title: '术语名称',
      dataIndex: 'term',
      key: 'term',
      width: 120,
      render: (text: string) => <Text strong>{text}</Text>,
    },
    {
      title: '定义',
      dataIndex: 'definition',
      key: 'definition',
      width: 200,
      ellipsis: true,
      render: (text: string) => <Text type="secondary">{text || '-'}</Text>,
    },
    {
      title: 'SQL 片段',
      dataIndex: 'sqlSnippet',
      key: 'sqlSnippet',
      ellipsis: true,
      render: (text: string) => (
        <SqlCodeBlock sql={text} variant="inline" truncateAt={36} showCopy={false} />
      ),
    },
    {
      title: '别名',
      dataIndex: 'aliases',
      key: 'aliases',
      width: 180,
      render: (aliases: string[]) => (
        <>
          {aliases && aliases.length > 0 ? (
            aliases.map((alias) => (
              <Tag key={alias} style={{ marginBottom: 2 }}>
                {alias}
              </Tag>
            ))
          ) : (
            <Text type="secondary">-</Text>
          )}
        </>
      ),
    },
    {
      title: '适用范围',
      dataIndex: 'orgScope',
      key: 'orgScope',
      width: 120,
      render: (scope: number[] | null) => {
        if (!scope || scope.length === 0) {
          return <Tag color="blue">全局</Tag>;
        }
        return <Tag>{scope.length} 个组织</Tag>;
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: unknown, record: Glossary) => (
        <Space size="small">
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            onClick={() => {
              setEditingItem(record);
              setDrawerVisible(true);
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

  const pagedGlossary = filteredGlossary.slice(
    (pagination.current - 1) * pagination.pageSize,
    pagination.current * pagination.pageSize
  );

  const tablePagination = useMemo(() => buildCompactTablePagination({
    current: pagination.current,
    pageSize: pagination.pageSize,
    total: filteredGlossary.length,
    showTotal: (total: number) => `共 ${total} 条`,
    onChange: (current: number, pageSize: number) => {
      setPagination({
        current,
        pageSize,
      });
    },
  }), [filteredGlossary.length, pagination.current, pagination.pageSize]);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Title level={4} style={{ marginBottom: 4 }}>术语词典管理</Title>
          <Paragraph type="secondary" style={{ margin: 0 }}>
            管理业务术语及其对应的 SQL 片段，支持别名和适用范围配置。
          </Paragraph>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setEditingItem(null);
            setDrawerVisible(true);
          }}
        >
          新增术语
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
          dataSource={pagedGlossary}
          rowKey="id"
          loading={loading}
          pagination={tablePagination}
          size="middle"
          locale={{ emptyText: '暂无数据' }}
        />
      </Card>

      <Drawer
        title={editingItem ? '编辑术语' : '新增术语'}
        open={drawerVisible}
        onClose={() => {
          setDrawerVisible(false);
          setEditingItem(null);
        }}
        width={480}
      >
        <GlossaryForm
          initialValues={editingItem || undefined}
          onSubmit={editingItem ? handleUpdate : handleCreate}
          onCancel={() => {
            setDrawerVisible(false);
            setEditingItem(null);
          }}
          visible={drawerVisible}
          orgTree={orgTree}
        />
      </Drawer>
    </div>
  );
};

export default GlossaryPage;
