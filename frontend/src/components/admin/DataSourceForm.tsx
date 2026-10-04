import React, { useEffect } from 'react';
import { Form, Input, Select, Switch, Button, Space } from 'antd';
import type { DataSource } from '@/types';

interface DataSourceFormProps {
  initialValues?: Partial<DataSource>;
  onSubmit: (values: Partial<DataSource>) => Promise<void>;
  onCancel: () => void;
  loading?: boolean;
}

const DB_TYPE_OPTIONS = [
  { label: 'PostgreSQL', value: 'postgresql' },
  { label: 'MySQL', value: 'mysql' },
  { label: 'SQL Server', value: 'sqlserver' },
];

const DataSourceForm: React.FC<DataSourceFormProps> = ({
  initialValues,
  onSubmit,
  onCancel,
  loading = false,
}) => {
  const [form] = Form.useForm();

  useEffect(() => {
    form.resetFields();
    form.setFieldsValue({
      name: initialValues?.name || '',
      dbType: initialValues?.dbType || 'postgresql',
      jdbcUrl: initialValues?.jdbcUrl || '',
      username: initialValues?.username || '',
      description: initialValues?.description || '',
      isReadOnly: initialValues?.isReadOnly ?? true,
      isActive: initialValues?.isActive ?? true,
    });
  }, [initialValues, form]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    if (initialValues?.id && !values.password) {
      delete values.password;
    }
    await onSubmit(values);
  };

  return (
    <Form form={form} layout="vertical">
      <Form.Item
        name="name"
        label="数据源名称"
        rules={[{ required: true, message: '请输入数据源名称' }]}
      >
        <Input placeholder="如：生产库、开发库" />
      </Form.Item>

      <Form.Item
        name="dbType"
        label="数据库类型"
        rules={[{ required: true, message: '请选择数据库类型' }]}
      >
        <Select options={DB_TYPE_OPTIONS} placeholder="选择数据库类型" />
      </Form.Item>

      <Form.Item
        name="jdbcUrl"
        label="JDBC URL"
        rules={[{ required: true, message: '请输入 JDBC URL' }]}
      >
        <Input
          placeholder="jdbc:postgresql://localhost:5432/dbname"
          style={{ fontFamily: 'monospace' }}
        />
      </Form.Item>

      <Form.Item
        name="username"
        label="用户名"
        rules={[{ required: true, message: '请输入用户名' }]}
      >
        <Input placeholder="数据库连接用户名" />
      </Form.Item>

      <Form.Item
        name="password"
        label="密码"
        extra={initialValues?.id ? '留空则保持原密码不变' : ''}
      >
        <Input.Password placeholder={initialValues?.id ? '输入新密码以更新' : '数据库连接密码'} />
      </Form.Item>

      <Form.Item name="description" label="描述">
        <Input.TextArea
          rows={2}
          placeholder="数据源的用途或说明..."
        />
      </Form.Item>

      <Form.Item name="isReadOnly" label="只读模式" valuePropName="checked">
        <Switch checkedChildren="只读" unCheckedChildren="读写" />
      </Form.Item>

      <Form.Item name="isActive" label="启用状态" valuePropName="checked" initialValue>
        <Switch checkedChildren="启用" unCheckedChildren="停用" />
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

export default DataSourceForm;
