import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Typography, Card, Table, Button, Space, Tag, Drawer, message, Input, DatePicker, TreeSelect, Select } from 'antd';
import { ReloadOutlined, LikeOutlined, DislikeOutlined, SearchOutlined, ClearOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { useQueryStore } from '@/store/useQueryStore';
import { useAuthStore } from '@/store/useAuthStore';
import { useOrgStore } from '@/store/useOrgStore';
import { fetchHistory, submitFeedback } from '@/services/queryApi';
import type { OrgNode, QueryHistory } from '@/types';
import { formatDateTime } from '@/utils/formatters';
import { buildCompactTablePagination } from '@/utils/tablePagination';
import {
  ResultTable,
  ResultChart,
  QuerySummary,
  ConfidenceBadge,
} from '@/components/query';
import { presentRequestError } from '@/utils/apiError';

const { Title, Paragraph } = Typography;
const { RangePicker } = DatePicker;

type ConfidenceFilter = 'all' | 'high' | 'medium' | 'low';

interface HistoryFilters {
  keyword: string;
  username: string;
  orgId?: string | number;
  dateRange: [Dayjs, Dayjs] | null;
  confidence: ConfidenceFilter;
}

interface HistoryOrgTreeData {
  value: string | number;
  label: string;
  children?: HistoryOrgTreeData[];
}

function buildHistoryOrgTreeData(node: OrgNode): HistoryOrgTreeData {
  return {
    value: node.id,
    label: node.level >= 3 ? `${node.name}（部门）` : node.name,
    children: node.children?.map((child) => buildHistoryOrgTreeData(child)),
  };
}

const CONFIDENCE_RANGES: Record<Exclude<ConfidenceFilter, 'all'>, { minConfidence: number; maxConfidence?: number }> = {
  high: { minConfidence: 0.9 },
  medium: { minConfidence: 0.7, maxConfidence: 0.8999 },
  low: { minConfidence: 0, maxConfidence: 0.6999 },
};

const EMPTY_FILTERS: HistoryFilters = {
  keyword: '',
  username: '',
  orgId: undefined,
  dateRange: null,
  confidence: 'all',
};

const HistoryPage: React.FC = () => {
  const navigate = useNavigate();
  const { history, setHistory, setResult, clearError } = useQueryStore();
  const { isManagerOrAbove } = useAuthStore();
  const { orgTree } = useOrgStore();
  const [selectedRecord, setSelectedRecord] = useState<QueryHistory | null>(null);
  const [drawerVisible, setDrawerVisible] = useState(false);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [filters, setFilters] = useState<HistoryFilters>(EMPTY_FILTERS);

  const buildHistoryParams = useCallback((page: number, pageSize: number, activeFilters: HistoryFilters) => {
    const confidenceRange = activeFilters.confidence !== 'all' ? CONFIDENCE_RANGES[activeFilters.confidence] : {};
    return {
      page,
      pageSize,
      keyword: activeFilters.keyword.trim() || undefined,
      username: isManagerOrAbove() ? activeFilters.username.trim() || undefined : undefined,
      orgId: isManagerOrAbove() ? activeFilters.orgId : undefined,
      startDate: activeFilters.dateRange?.[0].format('YYYY-MM-DD'),
      endDate: activeFilters.dateRange?.[1].format('YYYY-MM-DD'),
      ...confidenceRange,
    };
  }, [isManagerOrAbove]);

  const loadHistory = useCallback(async (page: number, pageSize: number, activeFilters: HistoryFilters) => {
    setLoading(true);
    try {
      const data = await fetchHistory(buildHistoryParams(page, pageSize, activeFilters));
      setHistory(data.list);
      setPagination({
        current: data.page ?? page,
        pageSize: data.pageSize ?? pageSize,
        total: data.total ?? data.list.length,
      });
    } catch (error) {
      presentRequestError(error, message.error, '加载历史记录失败');
    } finally {
      setLoading(false);
    }
  }, [buildHistoryParams, setHistory]);

  useEffect(() => {
    void loadHistory(1, 10, EMPTY_FILTERS);
  }, [loadHistory]);

  const handleReuse = useCallback((record: QueryHistory) => {
    if (record.response) {
      clearError();
      setResult(record.response);
    }
    navigate(`/query?q=${encodeURIComponent(record.question)}`);
  }, [clearError, navigate, setResult]);

  const handleFeedback = useCallback(async (requestId: string, feedback: 'good' | 'bad') => {
    try {
      await submitFeedback(requestId, feedback);
      message.success('感谢您的反馈');
      void loadHistory(pagination.current, pagination.pageSize, filters);
    } catch (error) {
      presentRequestError(error, message.error, '反馈提交失败');
    }
  }, [filters, loadHistory, pagination.current, pagination.pageSize]);

  const handleViewDetail = useCallback((record: QueryHistory) => {
    setSelectedRecord(record);
    setDrawerVisible(true);
  }, []);

  const handleSearch = () => {
    void loadHistory(1, pagination.pageSize, filters);
  };

  const handleReset = () => {
    setFilters(EMPTY_FILTERS);
    void loadHistory(1, pagination.pageSize, EMPTY_FILTERS);
  };

  const historyOrgTreeData = useMemo(
    () => orgTree.map((node) => buildHistoryOrgTreeData(node)),
    [orgTree],
  );

  const historyPagination = useMemo(() => buildCompactTablePagination({
    current: pagination.current,
    pageSize: pagination.pageSize,
    total: pagination.total,
    showTotal: (total: number) => `共 ${total} 条`,
    onChange: (page: number, pageSize: number) => {
      void loadHistory(page, pageSize, filters);
    },
  }), [filters, loadHistory, pagination.current, pagination.pageSize, pagination.total]);

  const columns = useMemo(() => [
    {
      title: '问题',
      dataIndex: 'question',
      key: 'question',
      ellipsis: true,
      render: (text: string) => <span style={{ fontWeight: 500 }}>{text}</span>,
    },
    {
      title: '用户',
      key: 'user',
      width: 130,
      render: (_: unknown, record: QueryHistory) => record.display_name || record.username || '-',
    },
    {
      title: '组织',
      dataIndex: 'org_name',
      key: 'org_name',
      width: 130,
      render: (text: string) => text || '-',
    },
    {
      title: '时间',
      dataIndex: 'created_at',
      key: 'created_at',
      width: 160,
      render: (text: string) => formatDateTime(text),
    },
    {
      title: '置信度',
      key: 'confidence',
      width: 100,
      render: (_: unknown, record: QueryHistory) => (
        <ConfidenceBadge confidence={record.response?.audit?.confidence ?? 0} />
      ),
    },
    {
      title: '反馈',
      dataIndex: 'feedback',
      key: 'feedback',
      width: 90,
      render: (feedback: 'good' | 'bad' | null) => {
        if (feedback === 'good') return <Tag color="success">满意</Tag>;
        if (feedback === 'bad') return <Tag color="error">不满意</Tag>;
        return <Tag>未反馈</Tag>;
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_: unknown, record: QueryHistory) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<ReloadOutlined />}
            onClick={() => handleReuse(record)}
          >
            复用
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => handleViewDetail(record)}
          >
            详情
          </Button>
          <Button
            type="text"
            size="small"
            icon={<LikeOutlined />}
            onClick={() => handleFeedback(record.request_id, 'good')}
          />
          <Button
            type="text"
            size="small"
            danger
            icon={<DislikeOutlined />}
            onClick={() => handleFeedback(record.request_id, 'bad')}
          />
        </Space>
      ),
    },
  ], [handleFeedback, handleReuse, handleViewDetail]);

  const renderDrawerContent = () => {
    if (!selectedRecord) return null;

    const { response } = selectedRecord;
    if (!response) return null;
    const recommendedChart = response.result_profile?.recommended_chart
      ?? (response.result_profile as { recommendedChart?: 'bar' | 'line' | 'pie' | 'table' | 'kpi' } | undefined)?.recommendedChart
      ?? 'table';

    return (
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <QuerySummary summary={response.summary} warnings={response.warnings || []} />
        <ConfidenceBadge confidence={response.audit?.confidence ?? 0} />

        {recommendedChart === 'table' ? (
          <ResultTable tableData={response.table} />
        ) : (
          <>
            <ResultChart tableData={response.table} chartType={recommendedChart} />
            <ResultTable tableData={response.table} />
          </>
        )}
      </Space>
    );
  };

  return (
    <div>
      <Title level={4}>查询历史</Title>
      <Paragraph>查看历史查询记录，支持复用和反馈。</Paragraph>

      <Card style={{ marginBottom: 16 }}>
        <Space wrap>
          <Input
            allowClear
            placeholder="搜索问题关键词"
            value={filters.keyword}
            onChange={(event) => setFilters((prev) => ({ ...prev, keyword: event.target.value }))}
            onPressEnter={handleSearch}
            style={{ width: 220 }}
          />
          {isManagerOrAbove() && (
            <>
              <Input
                allowClear
                placeholder="按用户名/姓名筛选"
                value={filters.username}
                onChange={(event) => setFilters((prev) => ({ ...prev, username: event.target.value }))}
                onPressEnter={handleSearch}
                style={{ width: 190 }}
              />
              <TreeSelect
                allowClear
                showSearch
                treeDefaultExpandAll
                placeholder="按公司/部门筛选"
                value={filters.orgId}
                treeData={historyOrgTreeData}
                onChange={(value) => setFilters((prev) => ({ ...prev, orgId: value }))}
                style={{ width: 220 }}
              />
            </>
          )}
          <RangePicker
            value={filters.dateRange}
            onChange={(value) => setFilters((prev) => ({
              ...prev,
              dateRange: value as [Dayjs, Dayjs] | null,
            }))}
          />
          <Select
            value={filters.confidence}
            onChange={(value) => setFilters((prev) => ({ ...prev, confidence: value }))}
            style={{ width: 150 }}
            options={[
              { value: 'all', label: '全部置信度' },
              { value: 'high', label: '高：≥ 0.90' },
              { value: 'medium', label: '中：0.70-0.89' },
              { value: 'low', label: '低：< 0.70' },
            ]}
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
            搜索
          </Button>
          <Button icon={<ClearOutlined />} onClick={handleReset}>
            重置
          </Button>
        </Space>
      </Card>

      <Card>
        <Table
          className="data-table data-table__compact-pagination"
          columns={columns}
          dataSource={history}
          rowKey="request_id"
          loading={loading}
          locale={{ emptyText: '暂无数据' }}
          pagination={historyPagination}
        />
      </Card>

      <Drawer
        title="查询详情"
        placement="right"
        width={600}
        open={drawerVisible}
        onClose={() => setDrawerVisible(false)}
      >
        {renderDrawerContent()}
      </Drawer>
    </div>
  );
};

export default HistoryPage;
