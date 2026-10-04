import React, { useMemo } from 'react';
import { App, Button, Tooltip } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { buildCompactSqlPreview, formatSqlForDisplay } from './sqlFormatter.js';

interface SqlCodeBlockProps {
  sql: string;
  variant?: 'block' | 'inline';
  maxHeight?: number;
  truncateAt?: number;
  showCopy?: boolean;
}

const SqlCodeBlock: React.FC<SqlCodeBlockProps> = ({
  sql,
  variant = 'block',
  maxHeight = 320,
  truncateAt = 56,
  showCopy = true,
}) => {
  const { message } = App.useApp();
  const formattedSql = useMemo(() => formatSqlForDisplay(sql), [sql]);
  const lineCount = useMemo(() => formattedSql.split('\n').length, [formattedSql]);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(sql);
      message.success('已复制原始 SQL');
    } catch {
      message.error('复制失败，请手动复制');
    }
  };

  if (variant === 'inline') {
    const displayText = buildCompactSqlPreview(sql, truncateAt);

    return (
      <Tooltip
        placement="topLeft"
        overlayClassName="sql-code-tooltip"
        title={(
          <div className="sql-code-tooltip__content">
            <pre className="sql-code-tooltip__pre">{formattedSql}</pre>
          </div>
        )}
      >
        <code className="sql-inline-chip">{displayText}</code>
      </Tooltip>
    );
  }

  return (
    <div className="sql-code-block">
      <div className="sql-code-block__toolbar">
        <div className="sql-code-block__meta">
          <span className="sql-code-block__badge">Formatted SQL</span>
          <span className="sql-code-block__line-count">{lineCount} lines</span>
        </div>
        {showCopy && (
          <Button
            type="text"
            size="small"
            className="sql-code-block__copy"
            icon={<CopyOutlined />}
            onClick={() => void handleCopy()}
          >
            复制格式化结果
          </Button>
        )}
      </div>
      <div className="sql-code-block__body" style={{ maxHeight }}>
        <pre className="sql-code-block__pre">{formattedSql}</pre>
      </div>
    </div>
  );
};

export default SqlCodeBlock;
