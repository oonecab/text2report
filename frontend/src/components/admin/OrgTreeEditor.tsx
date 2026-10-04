import React, { useState } from 'react';
import { Tree, Button, Modal, Form, Input, Select, message, Popconfirm, Space, Typography, List, Card } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ApartmentOutlined } from '@ant-design/icons';
import type { DataNode } from 'antd/es/tree';
import type { OrgNode } from '@/types';
import { presentRequestError } from '@/utils/apiError';

const { Text } = Typography;

interface OrgTreeEditorProps {
  treeData: OrgNode[];
  onAdd: (parentId: string | number | null, data: { name: string; code?: string; level: number }) => Promise<void>;
  onEdit: (id: string | number, data: { name: string; code?: string }) => Promise<void>;
  onDelete: (id: string | number) => Promise<void>;
  onSelect?: (node: OrgNode | null) => void;
  selectedNode?: OrgNode | null;
  loading?: boolean;
}

const LEVEL_OPTIONS = [
  { value: 1, label: '母公司' },
  { value: 2, label: '子公司' },
  { value: 3, label: '部门' },
];

// 获取层级标签，支持超出预定义范围的层级
const getLevelLabel = (level: number): string => {
  const option = LEVEL_OPTIONS.find((l) => l.value === level);
  if (option) return option.label;
  if (level > 3) return `第${level}级`;
  return '未知层级';
};

const OrgTreeEditor: React.FC<OrgTreeEditorProps> = ({
  treeData,
  onAdd,
  onEdit,
  onDelete,
  onSelect,
  selectedNode,
}) => {
  const [modalVisible, setModalVisible] = useState(false);
  const [editingNode, setEditingNode] = useState<OrgNode | null>(null);
  const [form] = Form.useForm();
  const [confirmLoading, setConfirmLoading] = useState(false);

  const buildTreeData = (nodes: OrgNode[]): DataNode[] => {
    return nodes.map((node) => ({
      key: String(node.id),
      title: node.name,
      icon: <ApartmentOutlined style={{ color: node.level === 1 ? '#1677ff' : node.level === 2 ? '#52c41a' : '#fa8c16' }} />,
      children: node.children && node.children.length > 0 ? buildTreeData(node.children) : undefined,
      isLeaf: node.level === 3 || !node.children?.length,
      nodeData: node,
    }));
  };

  const titleRender = (node: DataNode): React.ReactNode => {
    const nodeInfo = (node as DataNode & { nodeData?: OrgNode }).nodeData;
    if (!nodeInfo) return (node.title as React.ReactNode) ?? null;

    return (
      <Space>
        <ApartmentOutlined style={{ color: nodeInfo.level === 1 ? '#1677ff' : nodeInfo.level === 2 ? '#52c41a' : '#fa8c16' }} />
        <Text>{nodeInfo.name}</Text>
        {nodeInfo.code && <Text type="secondary" style={{ fontSize: 12 }}>({nodeInfo.code})</Text>}
        {selectedNode?.id === nodeInfo.id && (
          <Space size={4}>
            <Button
              type="text"
              size="small"
              icon={<PlusOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                handleAdd(nodeInfo.id);
              }}
              style={{ fontSize: 12 }}
            />
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                handleEdit(nodeInfo);
              }}
              style={{ fontSize: 12 }}
            />
            <Popconfirm
              title="确认删除"
              description="删除后无法恢复，确定要删除吗？"
              onConfirm={(e) => {
                e?.stopPropagation();
                handleDelete(nodeInfo.id);
              }}
              onCancel={(e) => e?.stopPropagation()}
              okText="删除"
              cancelText="取消"
            >
              <Button
                type="text"
                size="small"
                danger
                icon={<DeleteOutlined />}
                onClick={(e) => e.stopPropagation()}
                style={{ fontSize: 12 }}
              />
            </Popconfirm>
          </Space>
        )}
      </Space>
    );
  };

  const handleAdd = (parentId?: string | number) => {
    setEditingNode(null);
    form.resetFields();
    // Determine level based on parent
    const parentLevel = parentId ? (findNode(treeData, parentId)?.level || 1) : 1;
    const newLevel = parentId ? Math.min(parentLevel + 1, 3) : 1;
    form.setFieldsValue({ parentId: parentId || null, level: newLevel });
    setModalVisible(true);
  };

  const handleEdit = (node: OrgNode) => {
    setEditingNode(node);
    form.setFieldsValue({
      name: node.name,
      code: node.code,
    });
    setModalVisible(true);
  };

  const handleDelete = async (id: string | number) => {
    try {
      await onDelete(id);
      message.success('删除成功');
    } catch (err) {
      presentRequestError(err, message.error, '删除失败');
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setConfirmLoading(true);

      if (editingNode) {
        await onEdit(editingNode.id, { name: values.name, code: values.code });
        message.success('更新成功');
      } else {
        await onAdd(values.parentId, {
          name: values.name,
          code: values.code,
          level: values.level,
        });
        message.success('创建成功');
      }

      setModalVisible(false);
    } catch (err) {
      presentRequestError(err, message.error, editingNode ? '更新失败' : '创建失败');
    } finally {
      setConfirmLoading(false);
    }
  };

  const findNode = (nodes: OrgNode[], id: string | number): OrgNode | null => {
    for (const node of nodes) {
      if (String(node.id) === String(id)) return node;
      if (node.children) {
        const found = findNode(node.children, id);
        if (found) return found;
      }
    }
    return null;
  };

  // Get all selectable parent nodes for dropdown with depth tracking
  const getAllNodes = (
    nodes: OrgNode[],
    depth: number = 0,
    result: { id: string | number; name: string; level: number; parentId: string | number | null; depth: number }[] = []
  ): typeof result => {
    for (const node of nodes) {
      result.push({
        id: node.id,
        name: node.name,
        level: node.level ?? 1,
        parentId: node.parentId,
        depth,
      });
      if (node.children && node.children.length > 0) {
        getAllNodes(node.children, depth + 1, result);
      }
    }
    return result;
  };

  const allNodes = getAllNodes(treeData);

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => handleAdd()}
          size="small"
        >
          新增顶级节点
        </Button>
      </div>

      <Tree
        treeData={buildTreeData(treeData)}
        titleRender={titleRender}
        showIcon
        defaultExpandAll
        selectedKeys={selectedNode ? [String(selectedNode.id)] : []}
        onSelect={(_, info) => {
          if (info.selected) {
            const nodeId = String(info.node.key);
            const node = findNode(treeData, nodeId);
            onSelect?.(node);
          } else {
            onSelect?.(null);
          }
        }}
      />

      <Modal
        title={editingNode ? '编辑组织节点' : '新增组织节点'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        okText={editingNode ? '更新' : '创建'}
        cancelText="取消"
        confirmLoading={confirmLoading}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="name"
            label="节点名称"
            rules={[{ required: true, message: '请输入节点名称' }]}
          >
            <Input placeholder="如：华夏集团、华东分公司、销售一部" />
          </Form.Item>

          <Form.Item name="code" label="编码">
            <Input placeholder="可选，如：GROUP、SUB-A、DEPT-A1" />
          </Form.Item>

          {!editingNode && (
            <>
              <Form.Item
                name="level"
                label="层级"
                rules={[{ required: true, message: '请选择层级' }]}
              >
                <Select options={LEVEL_OPTIONS} placeholder="选择节点层级" />
              </Form.Item>

              <Form.Item name="parentId" label="父节点">
                <Select
                  allowClear
                  placeholder="选择父节点（不选则为顶级节点）"
                  options={allNodes.map((n) => {
                    // 基于深度计算层级标签（如果level字段无效则使用depth）
                    const effectiveLevel = n.level && n.level >= 1 ? n.level : n.depth + 1;
                    const levelLabel = getLevelLabel(effectiveLevel);
                    const indent = '　　'.repeat(n.depth); // 使用全角空格缩进
                    const prefix = n.depth > 0 ? '└─ ' : '';
                    return {
                      value: n.id,
                      label: `${indent}${prefix}${n.name} (${levelLabel})`,
                    };
                  })}
                />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>
    </div>
  );
};

// Side panel component for showing selected node details
interface OrgNodeDetailProps {
  node: OrgNode | null;
  onClose?: () => void;
}

export const OrgNodeDetail: React.FC<OrgNodeDetailProps> = ({ node }) => {
  if (!node) {
    return (
      <Card size="small">
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#999' }}>
          请选择左侧组织节点
        </div>
      </Card>
    );
  }

  const levelName = node.level === 1 ? '母公司' : node.level === 2 ? '子公司' : '部门';

  return (
    <Card size="small" title="节点详情">
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div>
          <Text type="secondary">节点名称</Text>
          <div style={{ fontSize: 16, fontWeight: 500 }}>{node.name}</div>
        </div>
        <div>
          <Text type="secondary">编码</Text>
          <div>{node.code || '-'}</div>
        </div>
        <div>
          <Text type="secondary">层级</Text>
          <div>
            <span style={{
              padding: '2px 8px',
              borderRadius: 4,
              background: node.level === 1 ? '#e6f4ff' : node.level === 2 ? '#f6ffed' : '#fff7e6',
              color: node.level === 1 ? '#1677ff' : node.level === 2 ? '#52c41a' : '#fa8c16',
              fontSize: 12,
            }}>
              {levelName}
            </span>
          </div>
        </div>
        <div>
          <Text type="secondary">子节点数量</Text>
          <div>{node.children?.length || 0} 个</div>
        </div>
        {node.children && node.children.length > 0 && (
          <div>
            <Text type="secondary">子节点列表</Text>
            <List
              size="small"
              dataSource={node.children}
              renderItem={(child) => (
                <List.Item style={{ padding: '8px 0' }}>
                  <Space>
                    <ApartmentOutlined style={{ color: child.level === 2 ? '#52c41a' : '#fa8c16' }} />
                    <Text>{child.name}</Text>
                    {child.code && <Text type="secondary" style={{ fontSize: 12 }}>({child.code})</Text>}
                  </Space>
                </List.Item>
              )}
              style={{ marginTop: 8 }}
            />
          </div>
        )}
      </div>
    </Card>
  );
};

export default OrgTreeEditor;
