import React from 'react';
import { Typography } from 'antd';
import { useAuthStore } from '@/store/useAuthStore';
import SqlCodeBlock from '@/components/common/SqlCodeBlock';

const { Text } = Typography;

interface SqlPreviewProps {
  sql?: string;
}

const SqlPreview: React.FC<SqlPreviewProps> = ({ sql }) => {
  const isAdmin = useAuthStore((state) => state.isAdmin());

  if (!isAdmin || !sql) {
    return null;
  }

  return (
    <div className="sql-preview-panel">
      <Text strong style={{ display: 'block', marginBottom: 12 }}>
        SQL 预览
      </Text>
      <SqlCodeBlock sql={sql} />
    </div>
  );
};

export default SqlPreview;
