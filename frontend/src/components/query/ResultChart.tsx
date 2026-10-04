import React from 'react';
import ReactECharts from 'echarts-for-react';
import { Card, Empty } from 'antd';
import type { TableData } from '@/types';
import { CHART_COLORS } from '@/utils/constants';

interface ResultChartProps {
  tableData: TableData;
  chartType: 'bar' | 'line' | 'pie' | 'table' | 'kpi';
}

interface NormalizedColumn {
  key: string;
  title: string;
  accessor: string;
}

const escapeHtml = (value: string) =>
  value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');

const ResultChart: React.FC<ResultChartProps> = ({ tableData, chartType }) => {
  const { columns, rows } = tableData;
  const sampleRowKeys = rows[0] ? Object.keys(rows[0]) : [];

  const normalizeMatchKey = (value: string) => value.toLowerCase().replace(/[\s_()%-]/g, '');
  const parseNumericValue = (value: unknown) => {
    if (typeof value === 'number') {
      return Number.isFinite(value) ? value : null;
    }
    if (typeof value === 'string') {
      const normalized = value.replace(/,/g, '').trim();
      if (normalized === '') {
        return null;
      }
      const parsed = Number(normalized);
      return Number.isFinite(parsed) ? parsed : null;
    }
    return null;
  };
  const isTemporalLabel = (value: string) => /年|月|日|季度|week|month|quarter|date|time/i.test(value);

  const resolveAccessor = (key: string, title: string) => {
    const candidates = [key, title].filter(Boolean);
    for (const candidate of candidates) {
      if (sampleRowKeys.includes(candidate)) {
        return candidate;
      }
    }

    for (const candidate of candidates) {
      const normalizedCandidate = normalizeMatchKey(candidate);
      const matched = sampleRowKeys.find((rowKey) => normalizeMatchKey(rowKey) === normalizedCandidate);
      if (matched) {
        return matched;
      }
    }

    return key || title;
  };

  const normalizedColumns: NormalizedColumn[] = (columns.length > 0
    ? columns.map((column) => {
        const key = typeof column === 'string' ? column : column.key || column.title;
        const title = typeof column === 'string' ? column : column.title || column.key;
        return {
          key,
          title,
          accessor: resolveAccessor(key, title),
        };
      })
    : sampleRowKeys.map((key) => ({
        key,
        title: key,
        accessor: key,
      })))
    .filter((column) => column.accessor);

  const isMetricColumn = (column: NormalizedColumn) => rows.some((row) => parseNumericValue(row[column.accessor]) !== null);
  const metricColumns = normalizedColumns.filter(isMetricColumn);
  const labelColumn = normalizedColumns.find((column) => !isMetricColumn(column))
    ?? normalizedColumns.find((column) => isTemporalLabel(column.title) || isTemporalLabel(column.key))
    ?? normalizedColumns[0];

  const usableMetricColumns = metricColumns.filter((column) => column.accessor !== labelColumn?.accessor);
  const resolvedChartType = (() => {
    if (!labelColumn || usableMetricColumns.length === 0 || rows.length === 0) {
      return null;
    }

    if (chartType === 'bar' || chartType === 'line' || chartType === 'pie') {
      return chartType;
    }

    const labelHint = `${labelColumn.key} ${labelColumn.title}`;
    if (isTemporalLabel(labelHint)) {
      return 'line';
    }
    if (usableMetricColumns.length === 1 && rows.length <= 8) {
      return 'bar';
    }
    if (usableMetricColumns.length <= 3) {
      return 'line';
    }
    return 'bar';
  })();

  if (!labelColumn || !resolvedChartType || usableMetricColumns.length === 0) {
    return (
      <Card size="small">
        <Empty description="当前结果暂无可展示图表" />
      </Card>
    );
  }

  const labels = rows.map((row) => String(row[labelColumn.accessor] ?? ''));

  const getOption = (effectiveChartType: 'bar' | 'line' | 'pie') => {
    switch (effectiveChartType) {
      case 'bar':
        return {
          tooltip: {
            trigger: 'axis',
            renderMode: 'richText',
            axisPointer: { type: 'shadow' },
          },
          legend: {
            data: usableMetricColumns.map((column) => column.title),
            bottom: 0,
          },
          grid: {
            left: '3%',
            right: '4%',
            bottom: '15%',
            top: '10%',
            containLabel: true,
          },
          xAxis: {
            type: 'category',
            data: labels,
          },
          yAxis: {
            type: 'value',
          },
          series: usableMetricColumns.map((column, idx) => ({
            name: column.title,
            type: 'bar',
            data: rows.map((row) => parseNumericValue(row[column.accessor])),
            itemStyle: {
              color: CHART_COLORS.series[idx % CHART_COLORS.series.length],
            },
          })),
        };

      case 'line':
        return {
          tooltip: {
            trigger: 'axis',
            renderMode: 'richText',
          },
          legend: {
            data: usableMetricColumns.map((column) => column.title),
            bottom: 0,
          },
          grid: {
            left: '3%',
            right: '4%',
            bottom: '15%',
            top: '10%',
            containLabel: true,
          },
          xAxis: {
            type: 'category',
            data: labels,
            boundaryGap: false,
          },
          yAxis: {
            type: 'value',
          },
          series: usableMetricColumns.map((column, idx) => ({
            name: column.title,
            type: 'line',
            data: rows.map((row) => parseNumericValue(row[column.accessor])),
            smooth: true,
            connectNulls: true,
            itemStyle: {
              color: CHART_COLORS.series[idx % CHART_COLORS.series.length],
            },
          })),
        };

      case 'pie':
        return {
          tooltip: {
            trigger: 'item',
            renderMode: 'richText',
            formatter: (params: { name?: string; value?: unknown; percent?: number }) =>
              `${escapeHtml(String(params.name ?? ''))}: ${String(params.value ?? '')} (${String(params.percent ?? 0)}%)`,
          },
          legend: {
            orient: 'vertical',
            right: 10,
            top: 'center',
          },
          series: [
            {
              type: 'pie',
              radius: ['40%', '70%'],
              center: ['40%', '50%'],
              avoidLabelOverlap: false,
              label: {
                show: false,
              },
              emphasis: {
                label: {
                  show: true,
                  fontSize: 14,
                  fontWeight: 'bold',
                },
              },
              data: rows
                .map((row, idx) => {
                  const value = parseNumericValue(row[usableMetricColumns[0].accessor]);
                  if (value === null) {
                    return null;
                  }
                  return {
                    name: String(row[labelColumn.accessor] ?? ''),
                    value,
                    itemStyle: {
                      color: CHART_COLORS.series[idx % CHART_COLORS.series.length],
                    },
                  };
                })
                .filter(Boolean),
            },
          ],
        };

      default:
        return {};
    }
  };

  return (
    <Card size="small">
      <ReactECharts
        option={getOption.call(null, resolvedChartType)}
        style={{ height: 300 }}
        opts={{ renderer: 'canvas' }}
      />
    </Card>
  );
};

export default ResultChart;
