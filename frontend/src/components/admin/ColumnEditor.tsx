import React, { useState } from 'react';
import { Button, Input, Popconfirm, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import type { MetaColumn, DDLParsedColumn } from '@/types';

const { Text } = Typography;

// Unified column type for both MetaColumn and DDLParsedColumn
type UnifiedColumn = (MetaColumn | DDLParsedColumn) & {
  _edited?: boolean;
  _id?: number | string;
};

interface ColumnEditorProps {
  columns: UnifiedColumn[];
  onChange?: (updated: UnifiedColumn[]) => void;
  loading?: boolean;
  readonly?: boolean;
  onDelete?: (column: UnifiedColumn) => void;
}

const ColumnEditor: React.FC<ColumnEditorProps> = ({
  columns,
  onChange,
  loading = false,
  readonly = false,
  onDelete,
}) => {
  const [editingKey, setEditingKey] = useState<string>('');
  const [editingField, setEditingField] = useState<'label' | 'description' | ''>('');
  const [tempValue, setTempValue] = useState('');

  const getCommentSourceTag = (record: UnifiedColumn) => {
    const commentSource = 'commentSource' in record ? record.commentSource : 'manual';
    const hasLabel = 'label' in record && !!record.label;
    const hasDescription = 'description' in record && !!record.description;

    if (commentSource === 'ddl') {
      return <Tag color="success">DDL</Tag>;
    }
    if (hasLabel || hasDescription) {
      return <Tag color="processing">手动</Tag>;
    }
    return <Tag color="warning">待填写</Tag>;
  };

  const getColumnId = (record: UnifiedColumn, index: number): string => {
    return 'id' in record ? String(record.id) : `new-${index}`;
  };

  const isEdited = (record: UnifiedColumn): boolean => {
    return '_edited' in record && !!record._edited;
  };

  const handleEdit = (id: string, field: 'label' | 'description', value: string) => {
    setEditingKey(id);
    setEditingField(field);
    setTempValue(value);
  };

  const handleSave = (_id: string, index: number) => {
    const updatedColumns = [...columns];
    const record = { ...updatedColumns[index], _edited: true };

    if (editingField === 'label') {
      record.label = tempValue;
      record.commentSource = 'manual';
    } else if (editingField === 'description') {
      record.description = tempValue;
      record.commentSource = 'manual';
    }

    updatedColumns[index] = record;
    setEditingKey('');
    setEditingField('');
    setTempValue('');
    onChange?.(updatedColumns);
  };

  const handleCancel = () => {
    setEditingKey('');
    setEditingField('');
    setTempValue('');
  };

  const handleKeyDown = (e: React.KeyboardEvent, id: string, index: number) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleSave(id, index);
    } else if (e.key === 'Escape') {
      handleCancel();
    }
  };

  const columns_def = [
    {
      title: '字段名',
      dataIndex: 'columnName',
      key: 'columnName',
      width: 150,
      fixed: 'left' as const,
      render: (text: string) => (
        <Text strong style={{ fontFamily: 'monospace' }}>
          {text}
        </Text>
      ),
    },
    {
      title: '数据类型',
      dataIndex: 'dataType',
      key: 'dataType',
      width: 140,
      render: (text: string) => (
        <Text type="secondary" style={{ fontFamily: 'monospace', fontSize: 12 }}>
          {text}
        </Text>
      ),
    },
    {
      title: '中文名',
      dataIndex: 'label',
      key: 'label',
      width: 150,
      render: (text: string, record: UnifiedColumn, index: number) => {
        const id = getColumnId(record, index);
        const isEditing = editingKey === id && editingField === 'label';

        if (readonly) {
          return text || <Text type="secondary">-</Text>;
        }

        if (isEditing) {
          return (
            <Input
              value={tempValue}
              onChange={(e) => setTempValue(e.target.value)}
              onBlur={() => handleSave(id, index)}
              onKeyDown={(e) => handleKeyDown(e, id, index)}
              autoFocus
              size="small"
              placeholder="请输入中文名"
            />
          );
        }

        return (
          <div
            onClick={() => handleEdit(id, 'label', text || '')}
            style={{
              cursor: 'pointer',
              padding: '4px 8px',
              borderRadius: 4,
              background: isEdited(record) ? '#fffbe6' : 'transparent',
              minHeight: 24,
            }}
          >
            {text || <Text type="secondary">点击填写</Text>}
          </div>
        );
      },
    },
    {
      title: '业务说明',
      dataIndex: 'description',
      key: 'description',
      render: (text: string, record: UnifiedColumn, index: number) => {
        const id = getColumnId(record, index);
        const isEditing = editingKey === id && editingField === 'description';

        if (readonly) {
          return text || <Text type="secondary">-</Text>;
        }

        if (isEditing) {
          return (
            <Input
              value={tempValue}
              onChange={(e) => setTempValue(e.target.value)}
              onBlur={() => handleSave(id, index)}
              onKeyDown={(e) => handleKeyDown(e, id, index)}
              autoFocus
              size="small"
              placeholder="请输入业务说明"
            />
          );
        }

        return (
          <div
            onClick={() => handleEdit(id, 'description', text || '')}
            style={{
              cursor: 'pointer',
              padding: '4px 8px',
              borderRadius: 4,
              background: isEdited(record) ? '#fffbe6' : 'transparent',
              minHeight: 24,
            }}
          >
            {text || <Text type="secondary">点击填写</Text>}
          </div>
        );
      },
    },
    {
      title: '注释来源',
      key: 'commentSource',
      width: 100,
      align: 'center' as const,
      render: (_: unknown, record: UnifiedColumn) => getCommentSourceTag(record),
    },
    ...(onDelete && !readonly ? [{
      title: '',
      key: 'actions',
      width: 56,
      align: 'center' as const,
      render: (_: unknown, record: UnifiedColumn) => (
        <Space>
          <Popconfirm
            title="删除字段"
            description={`确认删除字段 ${'columnName' in record ? record.columnName : ''}？`}
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => onDelete(record)}
          >
            <Tooltip title="删除字段">
              <Button danger type="text" size="small" icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    }] : []),
  ];

  // Add row background style for edited rows
  const rowClassName = (_record: UnifiedColumn, index: number) => {
    const record = columns[index];
    return isEdited(record) ? 'column-editor-edited-row' : '';
  };

  return (
    <Table
      columns={columns_def}
      dataSource={columns}
      rowKey={(record, index) => `column-${index}-${('columnName' in record ? record.columnName : 'unknown')}`}
      loading={loading}
      pagination={false}
      size="small"
      scroll={{ x: 650 }}
      rowClassName={rowClassName}
      style={{
        borderRadius: 8,
        overflow: 'hidden',
      }}
    />
  );
};

export default ColumnEditor;
