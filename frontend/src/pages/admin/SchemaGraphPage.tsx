import React, { useEffect, useMemo, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import { Alert, Card, Empty, Select, Space, Spin, Tag, Typography } from 'antd';
import type { EChartsOption } from 'echarts';
import {
  fetchJoinsByDatasource,
  fetchMetadataDatasources,
  fetchTablesByDatasource,
} from '@/services/metadataApi';

interface SchemaGraphDatasource {
  id: number;
  name: string;
}

interface SchemaGraphTable {
  id: number;
  datasourceId: number;
  tableName: string;
  label?: string;
  description?: string;
}

interface SchemaGraphJoinEdge {
  id: number;
  fromTable: string;
  fromColumn: string;
  toTable: string;
  toColumn: string;
  joinType: string;
  description?: string;
}

interface SchemaGraphNode {
  id: string;
  name: string;
  value: string;
  symbol: 'roundRect';
  symbolSize: [number, number];
  itemStyle: {
    color: string;
    borderColor: string;
    borderWidth: number;
    shadowBlur: number;
    shadowColor: string;
  };
  label: {
    show: boolean;
    position: 'inside';
    formatter: string;
    color: string;
    fontSize: number;
    lineHeight: number;
  };
  tooltip: {
    formatter: string;
  };
}

interface SchemaGraphLink {
  source: string;
  target: string;
  value: number;
  label: {
    show: false;
    formatter?: string;
    color: string;
    fontSize: number;
    backgroundColor: string;
    padding: [number, number];
    borderRadius: number;
  };
  lineStyle: {
    color: string;
    width: number;
    curveness: number;
    opacity: number;
  };
  emphasis: {
    lineStyle: {
      width: number;
      opacity: number;
    };
  };
  tooltip: {
    formatter: string;
  };
}

const { Title, Paragraph, Text } = Typography;

const JOIN_COLORS: Record<string, string> = {
  LEFT: '#1677ff',
  INNER: '#52c41a',
  RIGHT: '#fa8c16',
};

const escapeText = (value: string): string =>
  value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');

const formatTableLabel = (table: SchemaGraphTable): string => {
  const title = table.tableName;
  const subtitle = table.label?.trim() ? table.label.trim() : '未配置中文名';
  return `{tableName|${title}}\n{tableLabel|${subtitle}}`;
};

const formatEdgeTooltip = (join: SchemaGraphJoinEdge): string => {
  const normalizedJoinType = join.joinType.toUpperCase();
  const description = join.description?.trim() ? `\n${join.description.trim()}` : '';
  return [
    `${join.fromTable} -> ${join.toTable}`,
    `${normalizedJoinType} JOIN`,
    `${join.fromColumn} -> ${join.toColumn}`,
    description,
  ].join('');
};

const SchemaGraphPage: React.FC = () => {
  const [datasources, setDatasources] = useState<SchemaGraphDatasource[]>([]);
  const [selectedDatasourceId, setSelectedDatasourceId] = useState<number | undefined>(undefined);
  const [tables, setTables] = useState<SchemaGraphTable[]>([]);
  const [joins, setJoins] = useState<SchemaGraphJoinEdge[]>([]);
  const [datasourceLoading, setDatasourceLoading] = useState(false);
  const [graphLoading, setGraphLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string>('');

  useEffect(() => {
    const loadDatasources = async () => {
      setDatasourceLoading(true);
      setErrorMessage('');
      try {
        const result = await fetchMetadataDatasources();
        const normalized = result.map((item) => ({
          id: item.id,
          name: item.name,
        }));
        setDatasources(normalized);
        setSelectedDatasourceId((currentId) => currentId ?? normalized[0]?.id);
      } catch {
        setErrorMessage('加载数据源失败，请稍后重试。');
      } finally {
        setDatasourceLoading(false);
      }
    };

    void loadDatasources();
  }, []);

  useEffect(() => {
    if (selectedDatasourceId == null) {
      setTables([]);
      setJoins([]);
      return;
    }

    const loadGraphData = async () => {
      setGraphLoading(true);
      setErrorMessage('');
      try {
        const [tableResult, joinResult] = await Promise.all([
          fetchTablesByDatasource(selectedDatasourceId),
          fetchJoinsByDatasource(selectedDatasourceId),
        ]);
        setTables(
          tableResult.map((item) => ({
            id: item.id,
            datasourceId: item.datasourceId,
            tableName: item.tableName,
            label: item.label,
            description: item.description,
          }))
        );
        setJoins(
          joinResult.map((item) => ({
            id: item.id,
            fromTable: item.fromTable,
            fromColumn: item.fromColumn,
            toTable: item.toTable,
            toColumn: item.toColumn,
            joinType: item.joinType,
            description: item.description,
          }))
        );
      } catch {
        setTables([]);
        setJoins([]);
        setErrorMessage('加载表关系图失败，请稍后重试。');
      } finally {
        setGraphLoading(false);
      }
    };

    void loadGraphData();
  }, [selectedDatasourceId]);

  const graphNodes = useMemo<SchemaGraphNode[]>(() => {
    return tables.map((table) => ({
      id: table.tableName,
      name: table.tableName,
      value: table.description ?? '',
      symbol: 'roundRect',
      symbolSize: [176, 72],
      itemStyle: {
        color: '#fcfff5',
        borderColor: '#95de64',
        borderWidth: 1.5,
        shadowBlur: 14,
        shadowColor: 'rgba(34, 84, 61, 0.10)',
      },
      label: {
        show: true,
        position: 'inside',
        formatter: formatTableLabel(table),
        color: '#1f1f1f',
        fontSize: 13,
        lineHeight: 20,
      },
      tooltip: {
        formatter: table.description?.trim() ? escapeText(table.description) : '暂无表说明',
      },
    }));
  }, [tables]);

  const graphLinks = useMemo<SchemaGraphLink[]>(() => {
    return joins.map((join, index) => {
      const normalizedJoinType = join.joinType.toUpperCase();
      const lineColor = JOIN_COLORS[normalizedJoinType] ?? '#8c8c8c';
      const curveness = index % 2 === 0 ? 0.12 : 0.2;
      return {
        source: join.fromTable,
        target: join.toTable,
        value: index + 1,
        label: {
          show: false,
          color: '#595959',
          fontSize: 11,
          backgroundColor: 'rgba(255,255,255,0.96)',
          padding: [2, 8],
          borderRadius: 4,
        },
        lineStyle: {
          color: lineColor,
          width: 2.5,
          curveness,
          opacity: 0.72,
        },
        emphasis: {
          lineStyle: {
            width: 4,
            opacity: 1,
          },
        },
        tooltip: {
          formatter: formatEdgeTooltip(join),
        },
      };
    });
  }, [joins]);

  const chartOption = useMemo<EChartsOption>(() => {
    const option: EChartsOption = {
      tooltip: {
        trigger: 'item',
        confine: true,
        renderMode: 'richText',
        formatter: (params: unknown) => {
          if (!params || typeof params !== 'object' || !('data' in params)) {
            return '';
          }
          const data = (params as { data?: { tooltip?: { formatter?: string }; value?: string } }).data;
          return data?.tooltip?.formatter ?? escapeText(data?.value ?? '') ?? '';
        },
        backgroundColor: 'rgba(255,255,255,0.96)',
        borderColor: '#d9f7be',
        borderWidth: 1,
        textStyle: {
          color: '#262626',
          fontSize: 12,
          lineHeight: 18,
        },
      },
      animationDuration: 600,
      animationDurationUpdate: 300,
      series: [
        {
          type: 'graph',
          layout: 'force',
          roam: true,
          draggable: true,
          focusNodeAdjacency: true,
          edgeSymbol: ['none', 'arrow'] as [string, string],
          edgeSymbolSize: [0, 9] as [number, number],
          force: {
            repulsion: 520,
            edgeLength: 260,
            gravity: 0.04,
            friction: 0.12,
          },
          label: {
            position: 'inside',
            rich: {
              tableName: {
                fontSize: 13,
                fontWeight: 600,
                color: '#1f1f1f',
                lineHeight: 20,
              },
              tableLabel: {
                fontSize: 12,
                color: '#595959',
                lineHeight: 18,
              },
            },
          },
          edgeLabel: {
            show: false,
          },
          data: graphNodes,
          links: graphLinks,
          lineStyle: {
            opacity: 0.72,
          },
          scaleLimit: {
            min: 0.7,
            max: 1.6,
          },
        },
      ],
    };
    return option;
  }, [graphLinks, graphNodes]);

  const isEmpty = selectedDatasourceId == null || tables.length === 0 || joins.length === 0;

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Title level={4} style={{ marginBottom: 4 }}>
          数据源表关系图
        </Title>
        <Paragraph type="secondary" style={{ margin: 0 }}>
          只读展示当前数据源下的表节点与 JOIN 关系，帮助管理员快速查看结构连接情况。
        </Paragraph>
      </div>

      <Card>
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <div style={{ maxWidth: 360 }}>
            <Text strong style={{ display: 'block', marginBottom: 8 }}>
              选择数据源
            </Text>
            <Select
              style={{ width: '100%' }}
              placeholder="请选择数据源"
              loading={datasourceLoading}
              value={selectedDatasourceId}
              options={datasources.map((item) => ({
                value: item.id,
                label: item.name,
              }))}
              onChange={(value: number) => setSelectedDatasourceId(value)}
            />
          </div>

          {errorMessage ? <Alert type="error" showIcon message={errorMessage} /> : null}

          <Spin spinning={graphLoading}>
            <div
              style={{
                minHeight: 640,
                border: '1px solid #eef2e8',
                borderRadius: 12,
                background: 'radial-gradient(circle at top, #ffffff 0%, #f7fbf2 55%, #f4f7f7 100%)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: 20,
              }}
            >
              {isEmpty ? (
                <Empty description="当前数据源暂无可展示的表关系" />
              ) : (
                <ReactECharts
                  option={chartOption}
                  style={{ width: '100%', height: 640 }}
                  opts={{ renderer: 'canvas' }}
                />
              )}
            </div>
          </Spin>

          <Space size={16} wrap>
            <Text strong>图例说明</Text>
            <Tag color="blue">LEFT JOIN</Tag>
            <Tag color="green">INNER JOIN</Tag>
            <Tag color="orange">RIGHT JOIN</Tag>
            <Text type="secondary">字段连接详情可悬浮在线条上查看</Text>
          </Space>
        </Space>
      </Card>
    </div>
  );
};

export default SchemaGraphPage;
