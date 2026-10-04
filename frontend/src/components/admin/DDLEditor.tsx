import React, { useState } from 'react';
import { Input, Button, Upload, Space, Typography, message } from 'antd';
import { CloudUploadOutlined, ClearOutlined, FileTextOutlined } from '@ant-design/icons';
import { parseDDL } from '@/services/metadataApi';

const { Text } = Typography;

interface DDLEditorProps {
  onParseResult: (results: import('@/types').DDLParseResult[], warnings?: string[]) => void;
  onLoadingChange?: (loading: boolean) => void;
  datasourceId?: number;
  onDatasourceChange?: (id: number | undefined) => void;
}

const SAMPLE_DDL = `CREATE TABLE fact_gross_profit_detail (
    id           BIGSERIAL PRIMARY KEY,
    period_date  DATE NOT NULL,         -- 账期日期
    period_month CHAR(6) NOT NULL,      -- 账期月份，格式YYYYMM
    org_id       INTEGER NOT NULL,      -- 组织ID
    product_id   INTEGER NOT NULL,      -- 产品ID
    channel_id   INTEGER NOT NULL,      -- 渠道ID
    revenue      NUMERIC(18,2) NOT NULL,-- 销售收入
    cost         NUMERIC(18,2) NOT NULL,-- 销售成本
    gross_profit NUMERIC(18,2),         -- 毛利金额
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);`;

const DDLEditor: React.FC<DDLEditorProps> = ({ onParseResult, onLoadingChange, datasourceId }) => {
  const [value, setValue] = useState('');
  const [loading, setLoading] = useState(false);

  const handleParse = async () => {
    if (!value.trim()) {
      message.warning('请输入 DDL 语句');
      return;
    }

    setLoading(true);
    onLoadingChange?.(true);
    try {
      const result = await parseDDL(value, datasourceId);
      if (result.tables.length === 0) {
        message.warning('未解析到表结构，请检查 DDL 格式');
        return;
      }
      result.warnings?.forEach((warning) => {
        message.warning({ content: warning, duration: 6 });
      });
      onParseResult(result.tables, result.warnings);
    } catch (err) {
      message.error('解析失败，请检查 DDL 语法');
    } finally {
      setLoading(false);
      onLoadingChange?.(false);
    }
  };

  const handleClear = () => {
    setValue('');
  };

  const handleFileRead = (file: File) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      const content = e.target?.result as string;
      setValue(content);
      message.success(`已加载文件: ${file.name}`);
    };
    reader.onerror = () => {
      message.error('读取文件失败');
    };
    reader.readAsText(file);
    return false; // Prevent default upload behavior
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ marginBottom: 12 }}>
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          <Text type="secondary">
            支持以下 DDL 注释格式：<br />
            1. <code style={{ background: '#f5f5f5', padding: '2px 4px' }}>-- 字段说明</code>（行内注释）<br />
            2. <code style={{ background: '#f5f5f5', padding: '2px 4px' }}>sp_addextendedproperty</code>（SQL Server 扩展属性）<br />
            3. <code style={{ background: '#f5f5f5', padding: '2px 4px' }}>COMMENT ON COLUMN</code>（标准注释语法）
          </Text>
          <details style={{ cursor: 'pointer' }}>
            <summary style={{ color: '#1677ff' }}>查看示例 DDL</summary>
            <pre
              style={{
                background: '#f5f5f5',
                padding: 12,
                borderRadius: 4,
                fontSize: 12,
                fontFamily: 'monospace',
                whiteSpace: 'pre-wrap',
                cursor: 'pointer',
              }}
              onClick={() => setValue(SAMPLE_DDL)}
            >
              {SAMPLE_DDL}
            </pre>
          </details>
        </Space>
      </div>

      <Input.TextArea
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder="请粘贴 DDL 语句..."
        autoSize={{ minRows: 12, maxRows: 20 }}
        style={{
          fontFamily: 'monospace',
          fontSize: 13,
          flex: 1,
        }}
      />

      <div style={{ marginTop: 12, display: 'flex', gap: 8 }}>
        <Upload
          beforeUpload={handleFileRead}
          showUploadList={false}
          accept=".sql"
        >
          <Button icon={<FileTextOutlined />}>上传 .sql 文件</Button>
        </Upload>
        <Button
          icon={<ClearOutlined />}
          onClick={handleClear}
          disabled={!value}
        >
          清空
        </Button>
        <div style={{ flex: 1 }} />
        <Button
          type="primary"
          icon={<CloudUploadOutlined />}
          onClick={handleParse}
          loading={loading}
        >
          解析预览
        </Button>
      </div>
    </div>
  );
};

export default DDLEditor;
