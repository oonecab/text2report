import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Typography, Card, Table, Space, DatePicker, Select, Button, Statistic, Row, Col, Drawer, Tag, message } from 'antd';
import { FilterOutlined, ReloadOutlined } from '@ant-design/icons';
import { fetchAuditLogs, fetchAuditStats, fetchAuditLogDetail } from '@/services/auditApi';
import { ConfidenceBadge } from '@/components/query';
import SqlCodeBlock from '@/components/common/SqlCodeBlock';
import { formatDateTime } from '@/utils/formatters';
import { buildCompactTablePagination } from '@/utils/tablePagination';
import { presentRequestError } from '@/utils/apiError';

const { Title, Paragraph, Text } = Typography;
const { RangePicker } = DatePicker;

interface AuditLogItem {
  requestId: string;
  userId: string;
  username: string;
  displayName: string;
  orgId: string;
  orgName: string;
  question: string;
  sqlSnippet: string;
  status: 'success' | 'failed' | 'low_confidence';
  confidence: number;
  promptTokens: number;
  completionTokens: number;
  latencyMs: number;
  rowCount: number;
  createdAt: string;
}

interface AuditLogDetail extends AuditLogItem {
  fullSql: string;
  promptSummary: string;
  warnings: string[];
  retryCount: number;
  errorMessage: string | null;
}

interface AuditStats {
  totalQueries: number;
  successRate: number;
  avgLatencyMs: number;
  avgConfidence: number;
}

const AuditLogPage: React.FC = () => {
  const [logs, setLogs] = useState<AuditLogItem[]>([]);
  const [stats, setStats] = useState<AuditStats | null>(null);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [selectedLogDetail, setSelectedLogDetail] = useState<AuditLogDetail | null>(null);
  const [drawerVisible, setDrawerVisible] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [filters, setFilters] = useState<{
    startDate?: string;
    endDate?: string;
    status?: string;
  }>({});

  const loadStats = useCallback(async () => {
    try {
      const data = await fetchAuditStats();
      setStats(data);
    } catch {
      // Stats load silently fails
    }
  }, []);

  const loadLogs = useCallback(async (
    page: number,
    pageSize: number,
    nextFilters: typeof filters,
  ) => {
    setLoading(true);
    try {
      const data = await fetchAuditLogs({
        page,
        pageSize,
        ...nextFilters,
      });
      setLogs(data.list);
      setPagination({
        current: data.page ?? page,
        pageSize: data.pageSize ?? pageSize,
        total: data.total ?? data.list.length,
      });
    } catch (error) {
      presentRequestError(error, message.error, '加载审计日志失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadStats();
    void loadLogs(1, 10, {});
  }, [loadLogs, loadStats]);

  const handleViewDetail = useCallback(async (log: AuditLogItem) => {
    setDrawerVisible(true);
    setDetailLoading(true);
    setSelectedLogDetail(null);
    try {
      const detail = await fetchAuditLogDetail(log.requestId);
      setSelectedLogDetail(detail);
    } catch (error) {
      presentRequestError(error, message.error, '加载详情失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const handleFilter = () => {
    void loadLogs(1, pagination.pageSize, filters);
  };

  const handleReset = () => {
    const resetFilters = {};
    setFilters(resetFilters);
    void loadLogs(1, pagination.pageSize, resetFilters);
  };

  const auditPagination = useMemo(() => buildCompactTablePagination({
    current: pagination.current,
    pageSize: pagination.pageSize,
    total: pagination.total,
    showTotal: (total: number) => `共 ${total} 条`,
    onChange: (page: number, pageSize: number) => {
      void loadLogs(page, pageSize, filters);
    },
  }), [filters, loadLogs, pagination.current, pagination.pageSize, pagination.total]);

  const columns = useMemo(() => [
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 140,
      render: (text: string) => formatDateTime(text),
    },
    {
      title: '用户',
      dataIndex: 'displayName',
      key: 'displayName',
      width: 100,
    },
    {
      title: '问题',
      dataIndex: 'question',
      key: 'question',
      ellipsis: true,
    },
    {
      title: '组织',
      dataIndex: 'orgName',
      key: 'orgName',
      width: 130,
    },
    {
      title: '置信度',
      dataIndex: 'confidence',
      key: 'confidence',
      width: 100,
      render: (confidence: number) => <ConfidenceBadge confidence={confidence} />,
    },
    {
      title: '耗时',
      dataIndex: 'latencyMs',
      key: 'latencyMs',
      width: 90,
      render: (ms: number) => `${(ms / 1000).toFixed(1)}s`,
    },
    {
      title: '行数',
      dataIndex: 'rowCount',
      key: 'rowCount',
      width: 70,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (status: string) => {
        if (status === 'success') return <Tag color="success">成功</Tag>;
        if (status === 'low_confidence') return <Tag color="warning">低置信</Tag>;
        return <Tag color="error">失败</Tag>;
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_: unknown, record: AuditLogItem) => (
        <Button type="link" size="small" onClick={() => handleViewDetail(record)}>
          查看
        </Button>
      ),
    },
  ], [handleViewDetail]);

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Title level={4} style={{ marginBottom: 4 }}>审计日志</Title>
        <Paragraph type="secondary" style={{ margin: 0 }}>
          查看系统操作和查询的完整审计记录，用于问题排查和合规审计。
        </Paragraph>
      </div>

      {stats && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={6}>
            <Card size="small">
              <Statistic title="总查询数" value={stats.totalQueries} />
            </Card>
          </Col>
          <Col span={6}>
            <Card size="small">
              <Statistic title="成功率" value={stats.successRate} suffix="%" valueStyle={{ color: '#52c41a' }} />
            </Card>
          </Col>
          <Col span={6}>
            <Card size="small">
              <Statistic title="平均响应时间" value={stats.avgLatencyMs} suffix="ms" />
            </Card>
          </Col>
          <Col span={6}>
            <Card size="small">
              <Statistic title="平均置信度" value={(stats.avgConfidence * 100).toFixed(0)} suffix="%" />
            </Card>
          </Col>
        </Row>
      )}

      <Card>
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Space wrap>
            <RangePicker
              onChange={(_, dateStrings) => {
                setFilters({
                  ...filters,
                  startDate: dateStrings[0] || undefined,
                  endDate: dateStrings[1] || undefined,
                });
              }}
            />
            <Select
              placeholder="状态"
              style={{ width: 120 }}
              allowClear
              value={filters.status}
              onChange={(value) => setFilters({ ...filters, status: value })}
              options={[
                { value: 'success', label: '成功' },
                { value: 'low_confidence', label: '低置信' },
                { value: 'failed', label: '失败' },
              ]}
            />
            <Button type="primary" icon={<FilterOutlined />} onClick={handleFilter}>
              查询
            </Button>
            <Button icon={<ReloadOutlined />} onClick={handleReset}>
              重置
            </Button>
          </Space>

          <Table
            className="data-table data-table__compact-pagination"
            columns={columns}
            dataSource={logs}
            rowKey="requestId"
            loading={loading}
            pagination={auditPagination}
            size="middle"
            locale={{ emptyText: '暂无数据' }}
          />
        </Space>
      </Card>

      <Drawer
        title="审计详情"
        placement="right"
        width={600}
        open={drawerVisible}
        onClose={() => setDrawerVisible(false)}
      >
        {detailLoading && (
          <div style={{ textAlign: 'center', padding: 40 }}>加载中...</div>
        )}
        {selectedLogDetail && (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Card size="small" title="基本信息">
              <Row gutter={16}>
                <Col span={12}>
                  <Text type="secondary">请求 ID：</Text>
                  <Text code style={{ fontSize: 11 }}>{selectedLogDetail.requestId}</Text>
                </Col>
                <Col span={12}>
                  <Text type="secondary">状态：</Text>
                  {selectedLogDetail.status === 'success' && <Tag color="success">成功</Tag>}
                  {selectedLogDetail.status === 'low_confidence' && <Tag color="warning">低置信</Tag>}
                  {selectedLogDetail.status === 'failed' && <Tag color="error">失败</Tag>}
                </Col>
              </Row>
              <Row gutter={16} style={{ marginTop: 8 }}>
                <Col span={12}>
                  <Text type="secondary">用户：</Text>
                  {selectedLogDetail.displayName}
                </Col>
                <Col span={12}>
                  <Text type="secondary">组织：</Text>
                  {selectedLogDetail.orgName}
                </Col>
              </Row>
              <Row gutter={16} style={{ marginTop: 8 }}>
                <Col span={12}>
                  <Text type="secondary">耗时：</Text>
                  {(selectedLogDetail.latencyMs / 1000).toFixed(1)}s
                </Col>
                <Col span={12}>
                  <Text type="secondary">行数：</Text>
                  {selectedLogDetail.rowCount}
                </Col>
              </Row>
              {selectedLogDetail.retryCount > 0 && (
                <Row gutter={16} style={{ marginTop: 8 }}>
                  <Col span={12}>
                    <Text type="secondary">重试次数：</Text>
                    {selectedLogDetail.retryCount}
                  </Col>
                </Row>
              )}
            </Card>

            <Card size="small" title="问题">
              <Text>{selectedLogDetail.question}</Text>
            </Card>

            <Card size="small" title="SQL 预览">
              <SqlCodeBlock sql={selectedLogDetail.fullSql} maxHeight={360} />
            </Card>

            {selectedLogDetail.warnings && selectedLogDetail.warnings.length > 0 && (
              <Card size="small" title="警告">
                {selectedLogDetail.warnings.map((warning, idx) => (
                  <div key={idx} style={{ color: '#fa8c16', marginBottom: 4 }}>{warning}</div>
                ))}
              </Card>
            )}

            {selectedLogDetail.errorMessage && (
              <Card size="small" title="错误信息">
                <Text type="danger">{selectedLogDetail.errorMessage}</Text>
              </Card>
            )}

            <Card size="small" title="置信度">
              <ConfidenceBadge confidence={selectedLogDetail.confidence} />
            </Card>
          </Space>
        )}
      </Drawer>
    </div>
  );
};

export default AuditLogPage;
