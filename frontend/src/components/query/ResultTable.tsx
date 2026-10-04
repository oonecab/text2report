import React, { useMemo, useState } from 'react';
import { Table, Card, Typography } from 'antd';
import type { TableData } from '@/types';
import { buildCompactTablePagination } from '@/utils/tablePagination';

const { Text } = Typography;

interface ResultTableProps {
  tableData: TableData;
}

const ResultTable: React.FC<ResultTableProps> = ({ tableData }) => {
  const { columns, rows } = tableData;
  const [current, setCurrent] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  const tableColumns = useMemo(() =>
    columns.map((col) => {
      const normalized = typeof col === 'string'
        ? { key: col, title: col }
        : { key: col.key, title: col.title || col.key };

      return {
      title: normalized.title,
      dataIndex: normalized.key,
      key: normalized.key,
      ellipsis: true,
      sorter: (a: Record<string, unknown>, b: Record<string, unknown>) => {
        const aVal = a[normalized.key];
        const bVal = b[normalized.key];
        if (aVal === null || aVal === undefined) return 1;
        if (bVal === null || bVal === undefined) return -1;
        if (typeof aVal === 'number' && typeof bVal === 'number') {
          return aVal - bVal;
        }
        return String(aVal).localeCompare(String(bVal));
      },
      render: (value: unknown) => {
        if (value === null || value === undefined) {
          return <Text type="secondary">-</Text>;
        }
        if (typeof value === 'number') {
          return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
        }
        return String(value);
      },
    };
    }), [columns]);

  const pagination = useMemo(() => buildCompactTablePagination({
    current,
    pageSize,
    total: rows.length,
    showTotal: (total: number) => `共 ${total} 条`,
    onChange: (page: number, size: number) => {
      setCurrent(size !== pageSize ? 1 : page);
      setPageSize(size);
    },
  }), [current, pageSize, rows.length]);

  return (
    <Card size="small">
      <Table
        className="data-table data-table__compact-pagination"
        columns={tableColumns}
        dataSource={rows}
        rowKey={(_, index) => String(index)}
        pagination={pagination}
        size="small"
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: '暂无数据' }}
      />
    </Card>
  );
};

export default ResultTable;
