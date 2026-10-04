import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  message,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { ApiOutlined, CheckCircleOutlined, EditOutlined, PlusOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { AdminTableFilterBar } from '@/components/admin';
import {
  activateLlmProvider,
  createLlmProvider,
  disableLlmProvider,
  enableLlmProvider,
  fetchEnabledLlmProviders,
  fetchLlmProviders,
  fetchLlmRoutingPolicies,
  fetchLlmStatus,
  testLlmProvider,
  updateLlmProvider,
  updateLlmRoutingPolicy,
} from '@/services/modelConfigApi';
import type { LlmAvailability, LlmProvider, LlmProviderPayload, LlmRoutingPolicy } from '@/types';
import type { AdminTableFilterField, AdminTableFilterValues } from '@/components/admin';
import { presentRequestError } from '@/utils/apiError';
import { buildCompactTablePagination } from '@/utils/tablePagination';

const { Title, Paragraph, Text } = Typography;

const capabilityLabels: Record<LlmRoutingPolicy['capabilityCode'], string> = {
  SQL_GENERATION: 'SQL 生成',
  SQL_REPAIR: 'SQL 修复',
  RESULT_SUMMARY: '结果摘要',
};

const vendorOptions = [
  { label: 'OpenAI 兼容', value: 'OPENAI_COMPATIBLE' },
  { label: 'Gemini', value: 'GEMINI' },
  { label: 'Anthropic', value: 'ANTHROPIC' },
  { label: '火山原生', value: 'VOLCANO_NATIVE' },
  { label: 'Ollama', value: 'OLLAMA' },
  { label: '本地网关', value: 'LOCAL_GATEWAY' },
  { label: '自定义', value: 'CUSTOM' },
];

const ModelConfigPage: React.FC = () => {
  const [providers, setProviders] = useState<LlmProvider[]>([]);
  const [enabledProviders, setEnabledProviders] = useState<LlmProvider[]>([]);
  const [routingPolicies, setRoutingPolicies] = useState<LlmRoutingPolicy[]>([]);
  const [availability, setAvailability] = useState<LlmAvailability | null>(null);
  const [loading, setLoading] = useState(false);
  const [providerPagination, setProviderPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [providerFilters, setProviderFilters] = useState<AdminTableFilterValues>({});
  const [modalOpen, setModalOpen] = useState(false);
  const [editingProvider, setEditingProvider] = useState<LlmProvider | null>(null);
  const [form] = Form.useForm<LlmProviderPayload>();

  useEffect(() => {
    void loadData(1, providerPagination.pageSize, providerFilters);
  }, []);

  const loadData = async (
    targetPage = providerPagination.current,
    targetPageSize = providerPagination.pageSize,
    targetFilters = providerFilters,
  ) => {
    setLoading(true);
    try {
      const enabledFilter = targetFilters.enabled === 'true'
        ? true
        : targetFilters.enabled === 'false'
          ? false
          : undefined;
      const [providerPage, enabledList, routes, status] = await Promise.all([
        fetchLlmProviders({
          page: targetPage,
          pageSize: targetPageSize,
          keyword: typeof targetFilters.keyword === 'string' ? targetFilters.keyword : undefined,
          providerMode: typeof targetFilters.providerMode === 'string' ? targetFilters.providerMode : undefined,
          enabled: enabledFilter,
        }),
        fetchEnabledLlmProviders(),
        fetchLlmRoutingPolicies(),
        fetchLlmStatus(),
      ]);
      setProviders(providerPage.list ?? []);
      setProviderPagination({
        current: providerPage.page ?? targetPage,
        pageSize: providerPage.pageSize ?? targetPageSize,
        total: providerPage.total ?? providerPage.list?.length ?? 0,
      });
      setEnabledProviders(enabledList ?? []);
      setRoutingPolicies(routes ?? []);
      setAvailability(status);
    } catch (err) {
      presentRequestError(err, message.error, '加载模型配置失败');
    } finally {
      setLoading(false);
    }
  };

  const openCreateModal = (providerMode: 'ONLINE' | 'LOCAL') => {
    setEditingProvider(null);
    form.setFieldsValue({
      providerMode,
      vendorType: providerMode === 'LOCAL' ? 'LOCAL_GATEWAY' : 'OPENAI_COMPATIBLE',
      deploymentType: providerMode === 'LOCAL' ? 'LOCAL_GATEWAY' : 'SAAS',
      endpointPath: '/chat/completions',
      authType: providerMode === 'LOCAL' ? 'NONE' : 'BEARER',
      jsonMode: 'RESPONSE_FORMAT',
      timeoutSeconds: 60,
      temperature: 0.1,
      maxTokens: 2000,
      enabled: true,
      isDefault: providers.length === 0,
      priority: 0,
    });
    setModalOpen(true);
  };

  const openEditModal = (provider: LlmProvider) => {
    setEditingProvider(provider);
    form.setFieldsValue({
      ...provider,
      apiKey: undefined,
    });
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    try {
      if (editingProvider) {
        await updateLlmProvider(editingProvider.id, values);
        message.success('模型配置已更新');
      } else {
        await createLlmProvider(values);
        message.success('模型配置已创建');
      }
      setModalOpen(false);
      await loadData(editingProvider ? providerPagination.current : 1, providerPagination.pageSize, providerFilters);
    } catch (err) {
      presentRequestError(err, message.error, editingProvider ? '更新失败' : '创建失败');
    }
  };

  const handleToggleEnabled = async (provider: LlmProvider) => {
    try {
      if (provider.enabled) {
        await disableLlmProvider(provider.id);
        message.success('已停用');
      } else {
        await enableLlmProvider(provider.id);
        message.success('已启用');
      }
      await loadData(providerPagination.current, providerPagination.pageSize, providerFilters);
    } catch (err) {
      presentRequestError(err, message.error, '状态更新失败');
    }
  };

  const handleActivate = async (provider: LlmProvider) => {
    try {
      await activateLlmProvider(provider.id);
      message.success('已设为当前激活模型');
      await loadData(providerPagination.current, providerPagination.pageSize, providerFilters);
    } catch (err) {
      presentRequestError(err, message.error, '激活失败');
    }
  };

  const handleTest = async (provider: LlmProvider) => {
    try {
      const result = await testLlmProvider(provider.id);
      if (result.success) {
        message.success(`连接成功${result.latencyMs ? `，耗时 ${result.latencyMs}ms` : ''}`);
      } else {
        message.warning(result.message || '连接测试失败');
      }
      await loadData(providerPagination.current, providerPagination.pageSize, providerFilters);
    } catch (err) {
      presentRequestError(err, message.error, '连接测试失败');
    }
  };

  const handleRoutingChange = async (capability: LlmRoutingPolicy['capabilityCode'], providerId: number) => {
    try {
      await updateLlmRoutingPolicy(capability, providerId);
      message.success('路由策略已更新');
      await loadData(providerPagination.current, providerPagination.pageSize, providerFilters);
    } catch (err) {
      presentRequestError(err, message.error, '路由更新失败');
    }
  };

  const filterFields: AdminTableFilterField[] = [
    {
      name: 'keyword',
      label: '关键词',
      placeholder: '标识、名称、模型或地址',
      width: 240,
    },
    {
      name: 'providerMode',
      label: '模型类型',
      type: 'select',
      placeholder: '全部',
      options: [
        { label: '在线模型', value: 'ONLINE' },
        { label: '本地模型', value: 'LOCAL' },
      ],
    },
    {
      name: 'enabled',
      label: '状态',
      type: 'select',
      placeholder: '全部',
      options: [
        { label: '启用', value: 'true' },
        { label: '停用', value: 'false' },
      ],
    },
  ];

  const handleProviderSearch = (values: AdminTableFilterValues) => {
    setProviderFilters(values);
    void loadData(1, providerPagination.pageSize, values);
  };

  const handleProviderReset = () => {
    const resetFilters: AdminTableFilterValues = {};
    setProviderFilters(resetFilters);
    void loadData(1, providerPagination.pageSize, resetFilters);
  };

  const providerTablePagination = useMemo(() => buildCompactTablePagination({
    current: providerPagination.current,
    pageSize: providerPagination.pageSize,
    total: providerPagination.total,
    showTotal: (total: number) => `共 ${total} 条`,
    onChange: (page: number, pageSize: number) => {
      void loadData(
        pageSize !== providerPagination.pageSize ? 1 : page,
        pageSize,
        providerFilters,
      );
    },
  }), [providerFilters, providerPagination.current, providerPagination.pageSize, providerPagination.total]);

  const providerColumns = useMemo(() => [
    {
      title: '模型配置',
      dataIndex: 'displayName',
      key: 'displayName',
      width: 240,
      render: (_: string, record: LlmProvider) => (
        <Space direction="vertical" size={2}>
          <Space wrap>
            <Text strong>{record.displayName}</Text>
            {record.isDefault && <Tag color="gold">当前激活</Tag>}
            <Tag color={record.providerMode === 'ONLINE' ? 'blue' : 'purple'}>
              {record.providerMode === 'ONLINE' ? '在线' : '本地'}
            </Tag>
          </Space>
          <Text type="secondary" style={{ fontSize: 12 }}>{record.providerKey}</Text>
        </Space>
      ),
    },
    {
      title: '协议 / 模型',
      key: 'model',
      width: 170,
      render: (_: unknown, record: LlmProvider) => (
        <Space direction="vertical" size={2}>
          <Tag>{record.vendorType}</Tag>
          <Text>{record.modelCode}</Text>
        </Space>
      ),
    },
    {
      title: '地址',
      key: 'endpoint',
      width: 220,
      ellipsis: true,
      render: (_: unknown, record: LlmProvider) => (
        <Text type="secondary">{record.baseUrl || [record.host, record.port].filter(Boolean).join(':') || '-'}</Text>
      ),
    },
    {
      title: '状态',
      key: 'status',
      width: 150,
      render: (_: unknown, record: LlmProvider) => (
        <Space direction="vertical" size={4}>
          {record.enabled ? <Tag color="success">启用</Tag> : <Tag>停用</Tag>}
          {record.lastTestStatus && (
            <Tag color={record.lastTestStatus === 'SUCCESS' ? 'success' : 'error'}>{record.lastTestStatus}</Tag>
          )}
        </Space>
      ),
    },
    {
      title: '凭据',
      dataIndex: 'secretMask',
      key: 'secretMask',
      width: 130,
      render: (value: string) => <Text type="secondary">{value || '无'}</Text>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 300,
      render: (_: unknown, record: LlmProvider) => (
        <Space size="small" wrap>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEditModal(record)}>编辑</Button>
          <Button size="small" onClick={() => handleToggleEnabled(record)}>
            {record.enabled ? '停用' : '启用'}
          </Button>
          <Button size="small" icon={<ThunderboltOutlined />} onClick={() => handleTest(record)}>测试</Button>
          <Button
            size="small"
            type="primary"
            ghost={!record.isDefault}
            icon={<CheckCircleOutlined />}
            disabled={!record.enabled || record.isDefault}
            onClick={() => handleActivate(record)}
          >
            激活
          </Button>
        </Space>
      ),
    },
  ], [providers.length]);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Title level={4} style={{ marginBottom: 4 }}>模型配置中心</Title>
          <Paragraph type="secondary" style={{ margin: 0 }}>
            维护在线模型与本地模型，配置查询链路使用的 LLM 路由。
          </Paragraph>
        </div>
        <Space>
          <Button icon={<PlusOutlined />} onClick={() => openCreateModal('LOCAL')}>新增本地模型</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreateModal('ONLINE')}>新增在线模型</Button>
        </Space>
      </div>

      {availability && !availability.configured && (
        <Alert
          type="warning"
          showIcon
          message="当前没有可用模型配置"
          description={availability.message}
          style={{ marginBottom: 16 }}
        />
      )}

      <Card style={{ marginBottom: 16 }}>
        <AdminTableFilterBar
          fields={filterFields}
          values={providerFilters}
          onSearch={handleProviderSearch}
          onReset={handleProviderReset}
        />
        <Table
          columns={providerColumns}
          dataSource={providers}
          rowKey="id"
          loading={loading}
          pagination={providerTablePagination}
          scroll={{ x: 1100 }}
        />
      </Card>

      <Card title="能力路由">
        <Table
          dataSource={routingPolicies}
          rowKey="capabilityCode"
          pagination={false}
          loading={loading}
          columns={[
            {
              title: '能力',
              dataIndex: 'capabilityCode',
              render: (value: LlmRoutingPolicy['capabilityCode']) => capabilityLabels[value] || value,
            },
            {
              title: '当前模型',
              render: (_: unknown, record: LlmRoutingPolicy) => (
                <Space>
                  <ApiOutlined />
                  <Text>{record.providerName || record.providerKey || '-'}</Text>
                  {record.modelCode && <Tag>{record.modelCode}</Tag>}
                </Space>
              ),
            },
            {
              title: '切换',
              width: 280,
              render: (_: unknown, record: LlmRoutingPolicy) => (
                <Select
                  style={{ width: 240 }}
                  value={record.providerId}
                  options={enabledProviders.map((item) => ({
                    label: `${item.displayName} (${item.modelCode})`,
                    value: item.id,
                  }))}
                  onChange={(providerId) => handleRoutingChange(record.capabilityCode, providerId)}
                />
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={editingProvider ? '编辑模型配置' : '新增模型配置'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleSubmit}
        destroyOnHidden
        width={720}
      >
        <Form form={form} layout="vertical">
          <Space size="middle" style={{ width: '100%' }} align="start">
            <Form.Item name="providerMode" label="模型类型" rules={[{ required: true }]}>
              <Select
                style={{ width: 160 }}
                options={[
                  { label: '在线模型', value: 'ONLINE' },
                  { label: '本地模型', value: 'LOCAL' },
                ]}
              />
            </Form.Item>
            <Form.Item name="vendorType" label="协议/厂商" rules={[{ required: true }]}>
              <Select style={{ width: 180 }} options={vendorOptions} />
            </Form.Item>
            <Form.Item name="deploymentType" label="部署类型">
              <Select
                style={{ width: 180 }}
                options={[
                  { label: 'SaaS 在线', value: 'SAAS' },
                  { label: 'Ollama', value: 'OLLAMA' },
                  { label: '本地网关', value: 'LOCAL_GATEWAY' },
                ]}
              />
            </Form.Item>
          </Space>

          <Space size="middle" style={{ width: '100%' }} align="start">
            <Form.Item name="providerKey" label="唯一标识" rules={[{ required: true }]} style={{ flex: 1 }}>
              <Input placeholder="deepseek-default" />
            </Form.Item>
            <Form.Item name="displayName" label="展示名称" rules={[{ required: true }]} style={{ flex: 1 }}>
              <Input placeholder="DeepSeek 默认" />
            </Form.Item>
            <Form.Item name="modelCode" label="模型编码" rules={[{ required: true }]} style={{ flex: 1 }}>
              <Input placeholder="deepseek-chat" />
            </Form.Item>
          </Space>

          <Form.Item name="baseUrl" label="Base URL">
            <Input placeholder="https://api.deepseek.com/v1 或 http://127.0.0.1:8000/v1" />
          </Form.Item>

          <Space size="middle" style={{ width: '100%' }} align="start">
            <Form.Item name="host" label="本地主机" style={{ flex: 1 }}>
              <Input placeholder="http://127.0.0.1" />
            </Form.Item>
            <Form.Item name="port" label="端口">
              <InputNumber min={1} max={65535} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="endpointPath" label="端点路径" style={{ flex: 1 }}>
              <Input placeholder="/chat/completions" />
            </Form.Item>
          </Space>

          <Space size="middle" style={{ width: '100%' }} align="start">
            <Form.Item name="authType" label="鉴权方式">
              <Select
                style={{ width: 140 }}
                options={[
                  { label: 'Bearer', value: 'BEARER' },
                  { label: 'API Key', value: 'API_KEY' },
                  { label: 'AK/SK', value: 'AKSK' },
                  { label: '无', value: 'NONE' },
                ]}
              />
            </Form.Item>
            <Form.Item name="apiKey" label={editingProvider?.secretMask ? `API Key（当前 ${editingProvider.secretMask}）` : 'API Key'} style={{ flex: 1 }}>
              <Input.Password placeholder={editingProvider ? '留空表示不更新' : '请输入 API Key'} autoComplete="new-password" />
            </Form.Item>
          </Space>

          <Space size="middle" style={{ width: '100%' }} align="start">
            <Form.Item name="jsonMode" label="JSON 模式">
              <Select
                style={{ width: 180 }}
                options={[
                  { label: 'response_format', value: 'RESPONSE_FORMAT' },
                  { label: 'Prompt 约束', value: 'PROMPT_ONLY' },
                  { label: '混合', value: 'HYBRID' },
                  { label: '无', value: 'NONE' },
                ]}
              />
            </Form.Item>
            <Form.Item name="timeoutSeconds" label="超时秒数">
              <InputNumber min={1} max={300} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="temperature" label="温度">
              <InputNumber min={0} max={2} step={0.1} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="maxTokens" label="最大 Token">
              <InputNumber min={1} style={{ width: 140 }} />
            </Form.Item>
          </Space>

          <Space size="large">
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="isDefault" label="设为当前激活" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="priority" label="优先级">
              <InputNumber min={0} style={{ width: 120 }} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </div>
  );
};

export default ModelConfigPage;
