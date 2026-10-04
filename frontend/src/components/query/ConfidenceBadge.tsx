import React from 'react';
import { Tag } from 'antd';
import { CONFIDENCE_THRESHOLD } from '@/utils/constants';

interface ConfidenceBadgeProps {
  confidence: number;
}

const ConfidenceBadge: React.FC<ConfidenceBadgeProps> = ({ confidence }) => {
  const getColor = () => {
    if (confidence >= CONFIDENCE_THRESHOLD.high) {
      return 'success';
    }
    if (confidence >= CONFIDENCE_THRESHOLD.medium) {
      return 'warning';
    }
    return 'error';
  };

  const getLabel = () => {
    if (confidence >= CONFIDENCE_THRESHOLD.high) {
      return '高置信度';
    }
    if (confidence >= CONFIDENCE_THRESHOLD.medium) {
      return '中置信度';
    }
    return '低置信度';
  };

  return (
    <Tag color={getColor()}>
      {getLabel()} {(confidence * 100).toFixed(0)}%
    </Tag>
  );
};

export default ConfidenceBadge;
