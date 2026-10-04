import React, { useState } from 'react';
import { Input, Button, Space, Tag, Typography, Card } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { useOrgStore } from '@/store/useOrgStore';

const { Text } = Typography;

interface QueryInputProps {
  onSubmit: (question: string, orgId: string | number) => void;
  loading?: boolean;
  initialQuestion?: string;
  value?: string;
  onChange?: (value: string) => void;
  disabled?: boolean;
}

const SHORTCUT_TAGS = [
  '本月毛利',
  '各部门毛利率对比',
  '月度毛利趋势',
  '各子公司毛利占比',
  '本季度明细',
];

const QueryInput: React.FC<QueryInputProps> = ({
  onSubmit,
  loading = false,
  initialQuestion = '',
  value: externalValue,
  onChange: externalOnChange,
  disabled = false,
}) => {
  const [internalValue, setInternalValue] = useState(initialQuestion);
  const { currentOrg } = useOrgStore();

  // Support both controlled (external value) and uncontrolled mode
  const isControlled = externalValue !== undefined;
  const question = isControlled ? externalValue : internalValue;

  const handleChange = (newValue: string) => {
    if (isControlled) {
      externalOnChange?.(newValue);
    } else {
      setInternalValue(newValue);
    }
  };

  const handleSubmit = () => {
    if (disabled || !question.trim() || !currentOrg) return;
    onSubmit(question.trim(), currentOrg.id);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  };

  const handleTagClick = (tag: string) => {
    if (disabled) return;
    handleChange(tag);
  };

  return (
    <Card>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Text type="secondary" style={{ marginBottom: 8, display: 'block' }}>
            输入您的业务问题
          </Text>
          <Input.TextArea
            value={question}
            onChange={(e) => handleChange(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="例如：本月各部门毛利率对比"
            autoSize={{ minRows: 2, maxRows: 4 }}
            disabled={loading || disabled}
          />
        </div>

        <div>
          <Text type="secondary" style={{ fontSize: 12 }}>
            快捷问题：
          </Text>
          <div style={{ marginTop: 8 }}>
            {SHORTCUT_TAGS.map((tag) => (
              <Tag
                key={tag}
                onClick={() => handleTagClick(tag)}
                style={{ cursor: disabled ? 'not-allowed' : 'pointer', marginBottom: 4 }}
              >
                {tag}
              </Tag>
            ))}
          </div>
        </div>

        <div style={{ textAlign: 'right' }}>
          <Button
            type="primary"
            icon={<SendOutlined />}
            onClick={handleSubmit}
            loading={loading}
            disabled={disabled || !question.trim() || !currentOrg}
          >
            查询
          </Button>
          {!currentOrg && (
            <Text type="warning" style={{ marginLeft: 8, fontSize: 12 }}>
              请先在顶部选择组织范围
            </Text>
          )}
        </div>
      </Space>
    </Card>
  );
};

export default QueryInput;
