import React from 'react';
import { Card, Alert, Typography } from 'antd';

const { Text } = Typography;

interface QuerySummaryProps {
  summary: string;
  warnings: string[];
}

const QuerySummary: React.FC<QuerySummaryProps> = ({ summary, warnings }) => {
  return (
    <div style={{ marginBottom: 16 }}>
      <Card size="small" style={{ marginBottom: warnings.length > 0 ? 12 : 0 }}>
        <Text strong style={{ fontSize: 14 }}>查询摘要</Text>
        <div style={{ marginTop: 8 }}>
          <Text>{summary}</Text>
        </div>
      </Card>

      {warnings.length > 0 && (
        <Alert
          message="查询提醒"
          description={
            <ul style={{ margin: 0, paddingLeft: 20 }}>
              {warnings.map((warning, index) => (
                <li key={index}>{warning}</li>
              ))}
            </ul>
          }
          type="warning"
          showIcon
        />
      )}
    </div>
  );
};

export default QuerySummary;
