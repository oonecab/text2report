import React, { useEffect, useState } from 'react';
import { Typography, Card, Space, message, Tabs, Collapse, Tag, Alert, Button } from 'antd';
import { StarFilled, StarOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import {
  QueryInput,
  ResultTable,
  ResultChart,
  QuerySummary,
  SqlPreview,
  ConfidenceBadge,
  LoadingSkeleton,
} from '@/components/query';
import { useQueryStore } from '@/store/useQueryStore';
import { useAuthStore } from '@/store/useAuthStore';
import { useOrgStore } from '@/store/useOrgStore';
import { addToFavorites, removeFromFavorites } from '@/services/queryApi';
import { fetchLlmStatus } from '@/services/modelConfigApi';
import type { LlmAvailability } from '@/types';
import { presentRequestError } from '@/utils/apiError';

const EXAMPLE_QUESTIONS = [
  '华夏集团本月毛利率是多少？',
  '子公司A今年各部门毛利趋势',
  '上季度各子公司毛利额对比',
];

const { Title, Paragraph } = Typography;

const QueryPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const { currentResult, loading, error, submitQuery, clearError, setFavoriteStatus } = useQueryStore();
  const { isAdmin } = useAuthStore();
  const { currentOrg } = useOrgStore();
  const [favoriteLoading, setFavoriteLoading] = useState(false);
  const [modelStatus, setModelStatus] = useState<LlmAvailability | null>(null);
  const [modelChecking, setModelChecking] = useState(true);

  // URL parameter pre-fill for re-query
  const initialQuestion = searchParams.get('q') || '';
  const [inputValue, setInputValue] = useState(initialQuestion);
  const [lastQuestion, setLastQuestion] = useState(initialQuestion);

  useEffect(() => {
    const loadModelStatus = async () => {
      setModelChecking(true);
      try {
        const status = await fetchLlmStatus();
        setModelStatus(status);
      } catch (err) {
        setModelStatus({
          configured: false,
          message: err instanceof Error ? err.message : '模型配置检查失败，请联系管理员',
        });
      } finally {
        setModelChecking(false);
      }
    };
    loadModelStatus();
  }, []);

  const handleSubmit = async (question: string, orgId: string | number) => {
    if (!modelStatus?.configured) {
      message.warning(modelStatus?.message || '系统尚未配置可用模型，请联系管理员完成模型配置');
      return;
    }
    setLastQuestion(question);
    setInputValue(question);
    clearError();
    await submitQuery(question, orgId);
  };

  const handleExampleClick = (question: string) => {
    setInputValue(question);
  };

  const handleRetry = () => {
    if (lastQuestion && currentOrg) {
      handleSubmit(lastQuestion, currentOrg.id);
    }
  };

  const handleFavoriteToggle = async () => {
    if (!currentResult?.request_id) {
      return;
    }

    setFavoriteLoading(true);
    try {
      if (currentResult.is_favorited) {
        await removeFromFavorites(currentResult.request_id);
        setFavoriteStatus(false);
        message.success('已取消收藏');
      } else {
        await addToFavorites(currentResult.request_id);
        setFavoriteStatus(true);
        message.success('已加入收藏');
      }
    } catch (error) {
      presentRequestError(error, message.error, currentResult.is_favorited ? '取消收藏失败' : '收藏失败');
    } finally {
      setFavoriteLoading(false);
    }
  };

  const renderContent = () => {
    if (loading) {
      return <LoadingSkeleton />;
    }

    if (error) {
      return (
        <Card>
          <Alert
            type="error"
            message={error}
            action={
              <Button size="small" onClick={handleRetry}>
                重试
              </Button>
            }
            showIcon
          />
          <div style={{ marginTop: 16 }}>
            <p style={{ color: '#999', textAlign: 'center', padding: 20 }}>
              请在上方输入您的业务问题，开始查询
            </p>
            <Space wrap style={{ justifyContent: 'center' }}>
              {EXAMPLE_QUESTIONS.map(q => (
                <Tag
                  key={q}
                  style={{ cursor: 'pointer', padding: '4px 12px' }}
                  onClick={() => handleExampleClick(q)}
                >
                  {q}
                </Tag>
              ))}
            </Space>
          </div>
        </Card>
      );
    }

    if (!currentResult) {
      return (
        <Card>
          <p style={{ color: '#999', textAlign: 'center', padding: 20 }}>
            请在上方输入您的业务问题，开始查询
          </p>
          <Space wrap style={{ justifyContent: 'center' }}>
            {EXAMPLE_QUESTIONS.map(q => (
              <Tag
                key={q}
                style={{ cursor: 'pointer', padding: '4px 12px' }}
                onClick={() => handleExampleClick(q)}
              >
                {q}
              </Tag>
            ))}
          </Space>
        </Card>
      );
    }

    const { table, result_profile, summary, warnings, sql, audit } = currentResult;
    const recommendedChart = result_profile?.recommended_chart
      ?? (result_profile as { recommendedChart?: 'bar' | 'line' | 'pie' | 'table' | 'kpi' } | undefined)?.recommendedChart
      ?? 'table';

    const tabItems = [
      {
        key: 'summary',
        label: '摘要',
        children: (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <QuerySummary summary={summary} warnings={warnings} />
            <ConfidenceBadge confidence={audit?.confidence ?? 0} />
          </Space>
        ),
      },
      {
        key: 'chart',
        label: '图表',
        children: (
          <ResultChart tableData={table} chartType={recommendedChart} />
        ),
      },
      {
        key: 'table',
        label: '明细数据',
        children: (
          <ResultTable tableData={table} />
        ),
      },
    ];

    return (
      <Card style={{ marginTop: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
          <Button
            type={currentResult.is_favorited ? 'default' : 'primary'}
            ghost={!currentResult.is_favorited}
            icon={currentResult.is_favorited ? <StarFilled /> : <StarOutlined />}
            loading={favoriteLoading}
            onClick={handleFavoriteToggle}
          >
            {currentResult.is_favorited ? '已收藏' : '收藏查询'}
          </Button>
        </div>
        <Tabs
          defaultActiveKey="summary"
          items={tabItems}
        />
        {isAdmin() && sql && (
          <Collapse ghost className="query-sql-collapse" style={{ marginTop: 12 }}>
            <Collapse.Panel header="SQL（仅管理员可见）" key="sql">
              <SqlPreview sql={sql} />
            </Collapse.Panel>
          </Collapse>
        )}
      </Card>
    );
  };

  return (
    <div>
      <Title level={4}>智能查询</Title>
      <Paragraph>输入自然语言问题，系统自动生成报表。</Paragraph>

      <div style={{ marginBottom: 16 }}>
        {!modelChecking && modelStatus && !modelStatus.configured && (
          <Alert
            type="warning"
            showIcon
            message="模型未配置"
            description={modelStatus.message || '系统尚未配置可用模型，请联系管理员完成模型配置。'}
            style={{ marginBottom: 16 }}
          />
        )}
        {!modelChecking && modelStatus?.configured && (
          <Alert
            type="success"
            showIcon
            message={`当前模型：${modelStatus.displayName || modelStatus.providerKey || modelStatus.modelCode}`}
            description={`模型编码：${modelStatus.modelCode || '-'}，协议类型：${modelStatus.vendorType || '-'}`}
            style={{ marginBottom: 16 }}
          />
        )}
        <QueryInput
          onSubmit={handleSubmit}
          loading={loading || modelChecking}
          initialQuestion={initialQuestion}
          value={inputValue}
          onChange={setInputValue}
          disabled={!modelStatus?.configured}
        />
      </div>

      {renderContent()}
    </div>
  );
};

export default QueryPage;
