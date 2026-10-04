import React, { useEffect } from 'react';
import { Button, Form, Input, Select, Space } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';

export type AdminTableFilterPrimitive = string | number | undefined;
export type AdminTableFilterValues = Record<string, AdminTableFilterPrimitive>;

export interface AdminTableFilterField {
  name: string;
  label: string;
  placeholder?: string;
  type?: 'input' | 'select';
  options?: { label: string; value: string | number }[];
  allowClear?: boolean;
  width?: number;
}

interface AdminTableFilterBarProps {
  fields: AdminTableFilterField[];
  values: AdminTableFilterValues;
  onSearch: (values: AdminTableFilterValues) => void;
  onReset: () => void;
}

const normalizeValues = (values: AdminTableFilterValues): AdminTableFilterValues => {
  return Object.entries(values).reduce<AdminTableFilterValues>((result, [key, value]) => {
    if (typeof value === 'string') {
      const trimmed = value.trim();
      if (trimmed) {
        result[key] = trimmed;
      }
      return result;
    }
    if (value !== undefined) {
      result[key] = value;
    }
    return result;
  }, {});
};

const AdminTableFilterBar: React.FC<AdminTableFilterBarProps> = ({
  fields,
  values,
  onSearch,
  onReset,
}) => {
  const [form] = Form.useForm<AdminTableFilterValues>();

  useEffect(() => {
    form.setFieldsValue(values);
  }, [form, values]);

  const handleReset = () => {
    form.resetFields();
    onReset();
  };

  return (
    <Form
      form={form}
      layout="inline"
      initialValues={values}
      onFinish={(formValues) => onSearch(normalizeValues(formValues))}
      style={{ rowGap: 12, marginBottom: 16 }}
    >
      {fields.map((field) => (
        <Form.Item key={field.name} name={field.name} label={field.label}>
          {field.type === 'select' ? (
            <Select
              allowClear={field.allowClear ?? true}
              options={field.options}
              placeholder={field.placeholder}
              style={{ width: field.width ?? 160 }}
            />
          ) : (
            <Input
              allowClear={field.allowClear ?? true}
              placeholder={field.placeholder}
              style={{ width: field.width ?? 220 }}
            />
          )}
        </Form.Item>
      ))}
      <Form.Item>
        <Space size="small">
          <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
            查询
          </Button>
          <Button icon={<ReloadOutlined />} onClick={handleReset}>
            重置
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );
};

export default AdminTableFilterBar;
