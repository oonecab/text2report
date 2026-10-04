import React, { useState, useEffect } from 'react';
import { Typography, Card, Button, Input, Space, message, Statistic, Row, Col, Steps, Select } from 'antd';
import { ArrowLeftOutlined, CloudUploadOutlined, CheckOutlined } from '@ant-design/icons';
import { DDLEditor, ColumnEditor } from '@/components/admin';
import { confirmDDL } from '@/services/metadataApi';
import { fetchDataSources } from '@/services/dataSourceApi';
import type { DDLParseResult, DDLParsedColumn, DataSource } from '@/types';
import { presentRequestError } from '@/utils/apiError';

const { Title, Paragraph } = Typography;

const tableKey = (table: Pick<DDLParseResult, 'schemaName' | 'tableName'>) =>
  `${table.schemaName ?? ''}.${table.tableName}`;

const DDLUploadPage: React.FC = () => {
  const [phase, setPhase] = useState<'upload' | 'preview'>('upload');
  const [parseResults, setParseResults] = useState<DDLParseResult[]>([]);
  const [editedColumns, setEditedColumns] = useState<Record<string, DDLParsedColumn[]>>({});
  const [parseWarnings, setParseWarnings] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [datasources, setDatasources] = useState<DataSource[]>([]);
  const [selectedDatasourceId, setSelectedDatasourceId] = useState<number | undefined>(undefined);

  // Load active datasources on mount
  useEffect(() => {
    fetchDataSources(true)
      .then((ds) => {
        setDatasources(ds);
        if (ds.length > 0 && !selectedDatasourceId) {
          setSelectedDatasourceId(ds[0].id);
        }
      })
      .catch((error) => {
        presentRequestError(error, message.error, '加载数据源列表失败');
      });
  }, []);

  const handleParseResult = (results: DDLParseResult[], warnings: string[] = []) => {
    setParseResults(results);
    setParseWarnings(warnings);
    // Initialize edited columns
    const edited: Record<string, DDLParsedColumn[]> = {};
    for (const table of results) {
      edited[tableKey(table)] = [...table.columns];
    }
    setEditedColumns(edited);
    setPhase('preview');
  };

  const handleColumnChange = (tableName: string, updated: DDLParsedColumn[]) => {
    setEditedColumns((prev) => ({
      ...prev,
      [tableName]: updated,
    }));
  };

  const handleBack = () => {
    setPhase('upload');
    setParseResults([]);
    setEditedColumns({});
    setParseWarnings([]);
  };

  const handleSave = async () => {
    if (!selectedDatasourceId) {
      message.error('请选择数据源');
      return;
    }

    // Check for unfilled columns
    let unfilledCount = 0;
    for (const columns of Object.values(editedColumns)) {
      for (const col of columns) {
        if (col.commentSource === 'manual' && (!col.label || !col.description)) {
          unfilledCount++;
        }
      }
    }

    if (unfilledCount > 0) {
      const confirmed = await new Promise<boolean>((resolve) => {
        message.warning({
          content: `仍有 ${unfilledCount} 个字段未填写注释，确定继续？`,
          duration: 5,
        });
        // Auto confirm for mock, in real would show Modal.confirm
        resolve(true);
      });
      if (!confirmed) return;
    }

    setSaving(true);
    try {
      // Merge edited columns back to parse results
      const finalResults = parseResults.map((table) => ({
        ...table,
        columns: editedColumns[tableKey(table)] || table.columns,
      }));

      await confirmDDL({ parsedTables: finalResults, datasourceId: selectedDatasourceId });
      message.success('保存成功！元数据已导入数据库');
      message.info('DDL 已入库，表描述向量化已在后台异步启动');
      handleBack();
    } catch (error) {
      presentRequestError(error, message.error, '保存失败');
    } finally {
      setSaving(false);
    }
  };

  // Calculate statistics
  const totalColumns = Object.values(editedColumns).flat().length;
  const filledColumns = Object.values(editedColumns)
    .flat()
    .filter((c) => c.label && c.description).length;
  const ddlColumns = Object.values(editedColumns)
    .flat()
    .filter((c) => c.commentSource === 'ddl').length;
  const pendingColumns = totalColumns - filledColumns;

  const stepsItems = [
    { title: '上传 DDL', description: '粘贴或选择文件' },
    { title: '预览并补充注释', description: '确认字段信息后导入' },
  ];

  if (phase === 'upload') {
    return (
      <div>
        <div style={{ marginBottom: 16 }}>
          <Title level={4} style={{ marginBottom: 4 }}>DDL 上传解析</Title>
          <Paragraph type="secondary">
            上传或粘贴 DDL 语句，系统解析表结构和字段注释。已注释字段标记为「DDL」，未注释字段标记为「待填写」。
          </Paragraph>
        </div>

        <Steps current={0} items={stepsItems} style={{ marginBottom: 24 }} />

        <Card>
          <div style={{ marginBottom: 16 }}>
            <Space>
              <span style={{ color: '#666' }}>目标数据源：</span>
              <Select
                value={selectedDatasourceId}
                onChange={(value) => setSelectedDatasourceId(value)}
                options={datasources.map((ds) => ({ label: ds.name, value: ds.id }))}
                placeholder="请选择数据源"
                style={{ width: 200 }}
              />
            </Space>
          </div>
          <DDLEditor
            onParseResult={handleParseResult}
            onLoadingChange={setLoading}
            datasourceId={selectedDatasourceId}
          />
        </Card>
      </div>
    );
  }

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Title level={4} style={{ marginBottom: 4 }}>DDL 解析预览</Title>
        <Paragraph type="secondary">
          请检查解析结果，补充未填写的字段注释。确认无误后点击「保存并导入」。
        </Paragraph>
      </div>

      <Steps current={1} items={stepsItems} style={{ marginBottom: 24 }} />

      {parseWarnings.length > 0 && (
        <Card size="small" style={{ marginBottom: 16, borderColor: '#faad14' }}>
          <Space direction="vertical" size={4}>
            {parseWarnings.map((warning) => (
              <Paragraph key={warning} type="warning" style={{ margin: 0 }}>
                {warning}
              </Paragraph>
            ))}
          </Space>
        </Card>
      )}

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="总字段数"
              value={totalColumns}
              prefix={<CloudUploadOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="DDL 已有注释"
              value={ddlColumns}
              valueStyle={{ color: '#52c41a' }}
              prefix={<CheckOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="已填写"
              value={filledColumns - ddlColumns}
              valueStyle={{ color: '#1677ff' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="待填写"
              value={pendingColumns}
              valueStyle={{ color: pendingColumns > 0 ? '#fa8c16' : '#52c41a' }}
            />
          </Card>
        </Col>
      </Row>

      {parseResults.map((table) => (
        <Card key={tableKey(table)} style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 16 }}>
            <Space>
              <Title level={5} style={{ margin: 0 }}>表名：{table.tableName}</Title>
              {table.schemaName && (
                <span style={{ color: '#999', fontSize: 12 }}>({table.schemaName})</span>
              )}
            </Space>
          </div>

          <Input
            value={table.label || ''}
            placeholder="请输入表的中文名"
            style={{ width: 300, marginBottom: 12 }}
            onChange={(e) => {
              setParseResults((prev) =>
                prev.map((t) =>
                  tableKey(t) === tableKey(table) ? { ...t, label: e.target.value } : t
                )
              );
            }}
          />

          <Input.TextArea
            value={table.description || ''}
            placeholder="请输入表的说明"
            rows={2}
            style={{ marginBottom: 16 }}
            onChange={(e) => {
              setParseResults((prev) =>
                prev.map((t) =>
                  tableKey(t) === tableKey(table) ? { ...t, description: e.target.value } : t
                )
              );
            }}
          />

          <ColumnEditor
            columns={editedColumns[tableKey(table)] || []}
            onChange={(updated) => handleColumnChange(tableKey(table), updated as DDLParsedColumn[])}
            loading={loading}
          />
        </Card>
      ))}

      <Card>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={handleBack}>
            返回重新上传
          </Button>
          <Button
            type="primary"
            icon={<CloudUploadOutlined />}
            onClick={handleSave}
            loading={saving}
            disabled={parseWarnings.some((warning) => warning.includes('保存将被拒绝'))}
          >
            保存并导入元数据库
          </Button>
        </Space>
      </Card>
    </div>
  );
};

export default DDLUploadPage;
