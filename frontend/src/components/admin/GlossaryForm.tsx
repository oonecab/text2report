import React, { useEffect } from 'react';
import { Form, Input, Select, TreeSelect, Button, Space, message } from 'antd';
import type { Glossary } from '@/types';
import { presentRequestError } from '@/utils/apiError';

interface GlossaryFormProps {
  initialValues?: Partial<Glossary>;
  onSubmit: (values: Partial<Glossary>) => Promise<void>;
  onCancel: () => void;
  visible: boolean;
  orgTree?: import('@/types').OrgNode[];
}

const GlossaryForm: React.FC<GlossaryFormProps> = ({
  initialValues,
  onSubmit,
  onCancel,
  visible,
  orgTree = [],
}) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = React.useState(false);

  useEffect(() => {
    if (visible) {
      form.setFieldsValue({
        term: initialValues?.term || '',
        definition: initialValues?.definition || '',
        sqlSnippet: initialValues?.sqlSnippet || '',
        aliases: initialValues?.aliases || [],
        orgScope: initialValues?.orgScope || [],
      });
    }
  }, [visible, initialValues, form]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setLoading(true);
      await onSubmit(values);
      message.success(initialValues?.id ? '更新成功' : '创建成功');
      form.resetFields();
    } catch (err) {
      presentRequestError(err, message.error, initialValues?.id ? '更新失败' : '创建失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Form form={form} layout="vertical">
      <Form.Item
        name="term"
        label="术语名称"
        rules={[{ required: true, message: '请输入术语名称' }]}
      >
        <Input placeholder="如：毛利、毛利率、本季度" />
      </Form.Item>

      <Form.Item name="definition" label="定义说明">
        <Input.TextArea
          rows={3}
          placeholder="简要说明该术语的业务含义..."
        />
      </Form.Item>

      <Form.Item
        name="sqlSnippet"
        label="SQL 片段"
        rules={[{ required: true, message: '请输入 SQL 片段' }]}
      >
        <Input.TextArea
          rows={3}
          placeholder="对应的 SQL 表达式，如：revenue - cost"
          style={{ fontFamily: 'monospace' }}
        />
      </Form.Item>

      <Form.Item name="aliases" label="别名">
        <Select
          mode="tags"
          placeholder="输入别名后按回车添加，如：毛利润率、gross_margin"
          style={{ width: '100%' }}
        />
      </Form.Item>

      <Form.Item name="orgScope" label="适用组织范围">
        <TreeSelect
          treeData={orgTree.map((node) => ({
            title: node.name,
            value: node.id,
            key: node.id,
            children: node.children?.map((child) => ({
              title: child.name,
              value: child.id,
              key: child.id,
            })),
          }))}
          multiple
          allowClear
          placeholder="留空表示全局适用"
          treeDefaultExpandAll
          style={{ width: '100%' }}
        />
      </Form.Item>

      <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
        <Space>
          <Button onClick={onCancel}>取消</Button>
          <Button type="primary" onClick={handleSubmit} loading={loading}>
            {initialValues?.id ? '更新' : '创建'}
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );
};

export default GlossaryForm;
