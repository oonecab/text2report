import React from 'react';
import { Card, Skeleton } from 'antd';

const LoadingSkeleton: React.FC = () => (
  <Card>
    <Skeleton.Input block active style={{ width: 200, marginBottom: 12 }} />
    <Skeleton active paragraph={{ rows: 2 }} />
    <Skeleton.Input
      block
      active
      style={{ height: 260, marginTop: 16, borderRadius: 8, display: 'block' }}
    />
    <Skeleton active paragraph={{ rows: 6, style: { marginTop: 16 } }} />
  </Card>
);

export default LoadingSkeleton;
