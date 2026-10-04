import React, { useState, useEffect } from 'react';
import {
  Typography,
  Card,
  Input,
  List,
  Spin,
  Empty,
  Row,
  Col,
  Statistic,
  Divider,
  message,
  Space,
  Button,
  Switch,
  Tag,
  Modal,
  Form,
} from 'antd';
import { PlusOutlined, SearchOutlined, TableOutlined } from '@ant-design/icons';
import { ColumnEditor } from '@/components/admin';
import { createColumn, deleteColumn, fetchTables, fetchColumns, updateColumn, updateTable } from '@/services/metadataApi';
import type { MetaTable, MetaColumn } from '@/types';
import { presentRequestError } from '@/utils/apiError';

const { Title, Paragraph, Text } = Typography;
const { TextArea } = Input;

const TableListPage: React.FC = () => {
  const [tables, setTables] = useState<MetaTable[]>([]);
  const [selectedTable, setSelectedTable] = useState<MetaTable | null>(null);
  const [columns, setColumns] = useState<MetaColumn[]>([]);
  const [searchText, setSearchText] = useState('');
  const [loading, setLoading] = useState(false);
  const [columnsLoading, setColumnsLoading] = useState(false);
  const [tableDraft, setTableDraft] = useState({ label: '', description: '', isActive: true, isPublished: false });
  const [tableSaving, setTableSaving] = useState(false);
  const [tableEditing, setTableEditing] = useState(false);
  const [columnModalOpen, setColumnModalOpen] = useState(false);
  const [columnSaving, setColumnSaving] = useState(false);
  const [columnForm] = Form.useForm<Partial<MetaColumn>>();

  useEffect(() => {
    loadTables();
  }, []);

  useEffect(() => {
    if (selectedTable) {
      loadColumns(selectedTable.id);
    }
  }, [selectedTable]);

  useEffect(() => {
    setTableDraft({
      label: selectedTable?.label ?? '',
      description: selectedTable?.description ?? '',
      isActive: selectedTable?.isActive ?? true,
      isPublished: selectedTable?.isPublished ?? false,
    });
    setTableEditing(false);
  }, [selectedTable]);

  const updateSelectedTable = (updatedTable: MetaTable) => {
    setSelectedTable(updatedTable);
    setTables((prev) => prev.map((table) => (
      table.id === updatedTable.id ? updatedTable : table
    )));
  };

  const loadTables = async () => {
    setLoading(true);
    try {
      const data = await fetchTables();
      setTables(data);
      if (data.length > 0 && !selectedTable) {
        setSelectedTable(data[0]);
      }
    } catch (error) {
      presentRequestError(error, message.error, '加载表列表失败');
    } finally {
      setLoading(false);
    }
  };

  const loadColumns = async (tableId: number) => {
    setColumnsLoading(true);
    try {
      const data = await fetchColumns(tableId);
      setColumns(data);
    } catch (error) {
      presentRequestError(error, message.error, '加载字段列表失败');
    } finally {
      setColumnsLoading(false);
    }
  };

  const handleTableSave = async () => {
    if (!selectedTable) return;

    setTableSaving(true);
    try {
      const updatedTable = await updateTable(selectedTable.id, {
        label: tableDraft.label.trim() || undefined,
        description: tableDraft.description.trim() || undefined,
        isActive: tableDraft.isActive,
        isPublished: tableDraft.isActive && tableDraft.isPublished,
      });
      updateSelectedTable(updatedTable);
      setTableEditing(false);
      message.success(`数据表 ${updatedTable.tableName} 已更新`);
    } catch (error) {
      presentRequestError(error, message.error, '更新表信息失败');
    } finally {
      setTableSaving(false);
    }
  };

  const handleTableStatusChange = async (patch: Partial<MetaTable>) => {
    if (!selectedTable) return;

    setTableSaving(true);
    try {
      const nextActive = patch.isActive ?? selectedTable.isActive;
      const nextPublished = nextActive ? (patch.isPublished ?? selectedTable.isPublished ?? false) : false;
      const updatedTable = await updateTable(selectedTable.id, {
        label: selectedTable.label,
        description: selectedTable.description,
        isActive: nextActive,
        isPublished: nextPublished,
      });
      updateSelectedTable(updatedTable);
      setTableDraft((prev) => ({
        ...prev,
        isActive: updatedTable.isActive,
        isPublished: updatedTable.isPublished ?? false,
      }));
      message.success(`数据表 ${updatedTable.tableName} 状态已更新`);
    } catch (error) {
      presentRequestError(error, message.error, '更新表状态失败');
    } finally {
      setTableSaving(false);
    }
  };

  const handleColumnChange = async (updated: MetaColumn[]) => {
    for (let i = 0; i < updated.length; i++) {
      const newCol = updated[i];
      const oldCol = columns[i];
      if (newCol.label !== oldCol.label || newCol.description !== oldCol.description) {
        try {
          await updateColumn(newCol.id, {
            label: newCol.label,
            description: newCol.description,
            commentSource: newCol.commentSource,
          });
          message.success(`字段 ${newCol.columnName} 已更新`);
        } catch (error) {
          presentRequestError(error, message.error, `字段 ${newCol.columnName} 更新失败`);
        }
      }
    }
    setColumns(updated);
  };

  const handleCreateColumn = async () => {
    if (!selectedTable) return;

    try {
      const values = await columnForm.validateFields();
      setColumnSaving(true);
      const created = await createColumn(selectedTable.id, {
        columnName: values.columnName?.trim(),
        dataType: values.dataType?.trim(),
        label: values.label?.trim() || undefined,
        description: values.description?.trim() || undefined,
        commentSource: 'manual',
      });
      setColumns((prev) => [...prev, created]);
      setColumnModalOpen(false);
      columnForm.resetFields();
      message.success(`字段 ${created.columnName} 已添加`);
    } catch (error) {
      if (error && typeof error === 'object' && 'errorFields' in error) return;
      presentRequestError(error, message.error, '添加字段失败');
    } finally {
      setColumnSaving(false);
    }
  };

  const handleDeleteColumn = async (column: MetaColumn) => {
    try {
      await deleteColumn(column.id);
      setColumns((prev) => prev.filter((item) => item.id !== column.id));
      message.success(`字段 ${column.columnName} 已删除`);
    } catch (error) {
      presentRequestError(error, message.error, `字段 ${column.columnName} 删除失败`);
    }
  };

  const filteredTables = tables.filter(
    (t) =>
      t.tableName.toLowerCase().includes(searchText.toLowerCase()) ||
      (t.label && t.label.toLowerCase().includes(searchText.toLowerCase()))
  );

  const filledCount = columns.filter((c) => c.label && c.description).length;
  const ddlCount = columns.filter((c) => c.commentSource === 'ddl').length;

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Title level={4} style={{ marginBottom: 4 }}>表与字段管理</Title>
        <Paragraph type="secondary">
          查看已录入的数据表，编辑表和字段的中文名及业务说明。
        </Paragraph>
      </div>

      <Row gutter={24}>
        <Col span={7}>
          <Card
            title="数据表列表"
            size="small"
            extra={<span style={{ fontSize: 12, color: '#999' }}>{tables.length} 张表</span>}
          >
            <Input
              prefix={<SearchOutlined />}
              placeholder="搜索表名或中文名..."
              value={searchText}
              onChange={(e) => setSearchText(e.target.value)}
              style={{ marginBottom: 12 }}
            />

            {loading ? (
              <div style={{ textAlign: 'center', padding: 20 }}>
                <Spin />
              </div>
            ) : filteredTables.length === 0 ? (
              <Empty description="未找到匹配的表" />
            ) : (
              <List
                dataSource={filteredTables}
                renderItem={(table) => (
                  <List.Item
                    style={{
                      cursor: 'pointer',
                      background: selectedTable?.id === table.id ? '#e6f4ff' : 'transparent',
                      padding: '12px',
                      borderRadius: 4,
                      marginBottom: 4,
                    }}
                    onClick={() => setSelectedTable(table)}
                  >
                    <List.Item.Meta
                      avatar={<TableOutlined style={{ fontSize: 20, color: '#1677ff' }} />}
                      title={<Text strong>{table.tableName}</Text>}
                      description={
                        <Space direction="vertical" size={2}>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            {table.label || '未命名'}
                          </Text>
                          <Space size={4}>
                            <Tag color={table.isActive ? 'green' : 'default'}>{table.isActive ? 'Active' : 'Inactive'}</Tag>
                            <Tag color={table.isPublished ? 'blue' : 'default'}>{table.isPublished ? 'Published' : 'Draft'}</Tag>
                          </Space>
                        </Space>
                      }
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>

        <Col span={17}>
          {!selectedTable ? (
            <Card>
              <Empty description="请选择左侧的数据表" />
            </Card>
          ) : (
            <Card
              title={
                <Space>
                  <TableOutlined />
                  <span>{selectedTable.tableName}</span>
                  {selectedTable.label && <Text type="secondary">- {selectedTable.label}</Text>}
                  <Tag color={selectedTable.isActive ? 'green' : 'default'}>{selectedTable.isActive ? 'Active' : 'Inactive'}</Tag>
                  <Tag color={selectedTable.isPublished ? 'blue' : 'default'}>{selectedTable.isPublished ? 'Published' : 'Draft'}</Tag>
                </Space>
              }
              extra={
                <Text type="secondary">
                  {selectedTable.schemaName && `Schema: ${selectedTable.schemaName}`}
                </Text>
              }
            >
              <div style={{ marginBottom: 16 }}>
                <Row gutter={16} align="bottom">
                  <Col span={8}>
                    <Text strong style={{ display: 'block', marginBottom: 8 }}>表中文名</Text>
                    {tableEditing ? (
                      <Input
                        value={tableDraft.label}
                        onChange={(e) => setTableDraft((prev) => ({ ...prev, label: e.target.value }))}
                        placeholder="请输入表中文名"
                      />
                    ) : (
                      <div style={{ minHeight: 32, padding: '5px 11px', border: '1px solid #d9d9d9', borderRadius: 6, background: '#fafafa', display: 'flex', alignItems: 'center' }}>
                        {selectedTable.label || <Text type="secondary">未填写</Text>}
                      </div>
                    )}
                  </Col>
                  <Col span={8}>
                    <Text strong style={{ display: 'block', marginBottom: 8 }}>表业务说明</Text>
                    {tableEditing ? (
                      <TextArea
                        value={tableDraft.description}
                        onChange={(e) => setTableDraft((prev) => ({ ...prev, description: e.target.value }))}
                        placeholder="请输入表业务说明"
                        autoSize={{ minRows: 2, maxRows: 4 }}
                      />
                    ) : (
                      <div style={{ minHeight: 54, padding: '5px 11px', border: '1px solid #d9d9d9', borderRadius: 6, background: '#fafafa', whiteSpace: 'pre-wrap' }}>
                        {selectedTable.description || <Text type="secondary">未填写</Text>}
                      </div>
                    )}
                  </Col>
                  <Col span={4}>
                    <Text strong style={{ display: 'block', marginBottom: 8 }}>状态</Text>
                    <Space direction="vertical" size={8}>
                      <Space>
                        <Text>Active</Text>
                        <Switch size="small" checked={selectedTable.isActive} loading={tableSaving} onChange={(checked) => handleTableStatusChange({ isActive: checked })} />
                      </Space>
                      <Space>
                        <Text>Published</Text>
                        <Switch size="small" checked={!!selectedTable.isPublished} disabled={!selectedTable.isActive} loading={tableSaving} onChange={(checked) => handleTableStatusChange({ isPublished: checked })} />
                      </Space>
                    </Space>
                  </Col>
                  <Col span={4}>
                    <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
                      {tableEditing ? (
                        <>
                          <Button
                            onClick={() => {
                              setTableDraft({
                                label: selectedTable.label ?? '',
                                description: selectedTable.description ?? '',
                                isActive: selectedTable.isActive,
                                isPublished: selectedTable.isPublished ?? false,
                              });
                              setTableEditing(false);
                            }}
                            disabled={tableSaving}
                          >
                            取消
                          </Button>
                          <Button type="primary" onClick={handleTableSave} loading={tableSaving}>
                            保存表信息
                          </Button>
                        </>
                      ) : (
                        <Button type="primary" ghost onClick={() => setTableEditing(true)}>
                          编辑
                        </Button>
                      )}
                    </Space>
                  </Col>
                </Row>
              </div>

              <Row gutter={16} style={{ marginBottom: 16 }}>
                <Col span={8}>
                  <Statistic title="字段总数" value={columns.length} />
                </Col>
                <Col span={8}>
                  <Statistic title="DDL 注释" value={ddlCount} valueStyle={{ color: '#52c41a' }} />
                </Col>
                <Col span={8}>
                  <Statistic title="已填写" value={filledCount} valueStyle={{ color: filledCount === columns.length ? '#52c41a' : '#fa8c16' }} />
                </Col>
              </Row>

              <Divider style={{ margin: '16px 0' }} />

              {selectedTable.description && (
                <Paragraph type="secondary" style={{ marginBottom: 16 }}>
                  {selectedTable.description}
                </Paragraph>
              )}

              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                <Text strong>字段列表</Text>
                <Button type="primary" icon={<PlusOutlined />} disabled={!selectedTable.isActive} onClick={() => setColumnModalOpen(true)}>
                  添加字段
                </Button>
              </div>

              {columnsLoading ? (
                <div style={{ textAlign: 'center', padding: 40 }}>
                  <Spin size="large" />
                </div>
              ) : (
                <ColumnEditor
                  columns={columns}
                  onChange={(updated) => handleColumnChange(updated as MetaColumn[])}
                  onDelete={(column) => handleDeleteColumn(column as MetaColumn)}
                  loading={columnsLoading}
                  readonly={!selectedTable.isActive}
                />
              )}
            </Card>
          )}
        </Col>
      </Row>

      <Modal
        title="添加字段"
        open={columnModalOpen}
        onCancel={() => {
          setColumnModalOpen(false);
          columnForm.resetFields();
        }}
        onOk={handleCreateColumn}
        confirmLoading={columnSaving}
        okText="添加"
        cancelText="取消"
      >
        <Form form={columnForm} layout="vertical">
          <Form.Item
            name="columnName"
            label="字段名"
            rules={[
              { required: true, message: '请输入字段名' },
              { pattern: /^[A-Za-z_][A-Za-z0-9_]*$/, message: '字段名只能包含字母、数字和下划线，且不能以数字开头' },
            ]}
          >
            <Input placeholder="例如 gross_margin_rate" />
          </Form.Item>
          <Form.Item name="dataType" label="数据类型" rules={[{ required: true, message: '请输入数据类型' }]}>
            <Input placeholder="例如 numeric(18, 2)" />
          </Form.Item>
          <Form.Item name="label" label="中文名">
            <Input placeholder="请输入中文名" />
          </Form.Item>
          <Form.Item name="description" label="业务说明">
            <TextArea placeholder="请输入业务说明" autoSize={{ minRows: 2, maxRows: 4 }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default TableListPage;
