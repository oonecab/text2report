import React, { useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Input, InputNumber, Row, Select, Space, Statistic, Table, Tag, message } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { embeddingApi } from '@/services/embeddingApi';
import type { EmbeddingSearchResult, EmbeddingStats } from '@/types/user';
import { formatDateTime, nowInBeijing } from '@/utils/formatters';
import { presentRequestError } from '@/utils/apiError';

const LAST_SYNC_KEY = 'embedding-last-sync-at';

const EmbeddingManagePage: React.FC = () => {
  const [stats, setStats] = useState<EmbeddingStats>({ sql_example: 0, glossary: 0, table_description: 0, total: 0 });
  const [results, setResults] = useState<EmbeddingSearchResult[]>([]);
  const [loadingStats, setLoadingStats] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [searching, setSearching] = useState(false);
  const [form] = Form.useForm<{ sourceType: string; topK: number; query: string }>();
  const [lastSyncAt, setLastSyncAt] = useState<string | null>(() => localStorage.getItem(LAST_SYNC_KEY));

  const statsCards = useMemo(() => ([
    { title: '问答示例向量数', value: stats.sql_example },
    { title: '术语词典向量数', value: stats.glossary },
    { title: '表描述向量数', value: stats.table_description },
    { title: '总计', value: stats.total },
  ]), [stats]);

  const loadStats = async () => {
    setLoadingStats(true);
    try {
      const data = await embeddingApi.stats();
      setStats(data);
    } catch (error) {
      presentRequestError(error, message.error, '加载向量统计失败');
    } finally {
      setLoadingStats(false);
    }
  };

  useEffect(() => {
    void loadStats();
    form.setFieldsValue({ sourceType: 'sql_example', topK: 5, query: '' });
  }, []);

  const handleSync = async () => {
    setSyncing(true);
    try {
      const result = await embeddingApi.sync();
      const now = nowInBeijing();
      localStorage.setItem(LAST_SYNC_KEY, now);
      setLastSyncAt(now);
      message.success(`同步完成：问答示例 ${result.examples} 条，术语词典 ${result.glossary} 条，表描述 ${result.tables} 条`);
      await loadStats();
    } catch (error) {
      presentRequestError(error, message.error, '全量同步失败');
    } finally {
      setSyncing(false);
    }
  };

  const handleSearch = async () => {
    try {
      const values = await form.validateFields();
      setSearching(true);
      const data = await embeddingApi.testSearch(values.query, values.sourceType, values.topK);
      setResults(data);
    } catch (error) {
      presentRequestError(error, message.error, '检索测试失败');
    } finally {
      setSearching(false);
    }
  };

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <h2 style={{ marginBottom: 4 }}>向量检索管理</h2>
        <div style={{ color: '#666' }}>查看 embedding 统计、触发全量同步，并验证不同类型元数据的检索效果。</div>
      </div>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        {statsCards.map((card) => (
          <Col span={6} key={card.title}>
            <Card loading={loadingStats}>
              <Statistic title={card.title} value={card.value} />
            </Card>
          </Col>
        ))}
      </Row>

      <Card style={{ marginBottom: 16 }}>
        <Space size="middle" style={{ width: '100%', justifyContent: 'space-between' }}>
          <Space>
            <Button type="primary" icon={<ThunderboltOutlined />} loading={syncing} onClick={() => void handleSync()}>
              全量同步
            </Button>
            <span style={{ color: '#666' }}>
              上次同步：{lastSyncAt ? formatDateTime(lastSyncAt) : '从未同步'}
            </span>
          </Space>
        </Space>
      </Card>

      <Card title="检索测试">
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={6}>
              <Form.Item
                label="类型"
                name="sourceType"
                rules={[{ required: true, message: '请选择检索类型' }]}
              >
                <Select
                  options={[
                    { label: '问答示例', value: 'sql_example' },
                    { label: '术语词典', value: 'glossary' },
                    { label: '表描述', value: 'table_description' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={4}>
              <Form.Item
                label="Top K"
                name="topK"
                rules={[{ required: true, message: '请输入 Top K' }]}
              >
                <InputNumber min={1} max={10} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={14}>
              <Form.Item
                label="测试问题"
                name="query"
                rules={[{ required: true, message: '请输入测试问题' }]}
              >
                <Input placeholder="例如：查询季度毛利率的示例 SQL" />
              </Form.Item>
            </Col>
          </Row>
          <Button type="primary" loading={searching} onClick={() => void handleSearch()}>
            测试检索
          </Button>
        </Form>

        <Table
          style={{ marginTop: 16 }}
          rowKey="id"
          loading={searching}
          dataSource={results}
          pagination={false}
          columns={[
            {
              title: '序号',
              key: 'index',
              width: 80,
              render: (_: unknown, __: EmbeddingSearchResult, index: number) => index + 1,
            },
            {
              title: '内容',
              dataIndex: 'contentText',
              key: 'contentText',
              ellipsis: true,
            },
            {
              title: '相似度',
              dataIndex: 'similarity',
              key: 'similarity',
              width: 140,
              render: (value?: number) => {
                const text = value != null ? value.toFixed(4) : '-';
                return value != null && value >= 0.65 ? <Tag color="green">{text}</Tag> : <Tag>{text}</Tag>;
              },
            },
          ]}
        />
      </Card>
    </div>
  );
};

export default EmbeddingManagePage;
