import React, { useEffect, useState } from 'react';
import { Typography, Card, Table, Button, Space, Tag, Empty, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { StarOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { fetchFavorites, removeFromFavorites } from '@/services/queryApi';
import type { FavoriteItem } from '@/types';
import { formatDate } from '@/utils/formatters';
import { presentRequestError } from '@/utils/apiError';

const { Title, Paragraph } = Typography;

const chartColors: Record<string, string> = {
  bar: 'blue',
  line: 'green',
  pie: 'orange',
  table: 'purple',
};

const FavoritesPage: React.FC = () => {
  const navigate = useNavigate();
  const [favorites, setFavorites] = useState<FavoriteItem[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    loadFavorites();
  }, []);

  const loadFavorites = async () => {
    setLoading(true);
    try {
      const data = await fetchFavorites();
      setFavorites(data);
    } catch (error) {
      presentRequestError(error, message.error, '加载收藏失败');
    } finally {
      setLoading(false);
    }
  };

  const handleRerun = (item: FavoriteItem) => {
    navigate(`/query?q=${encodeURIComponent(item.question)}`);
  };

  const handleRemove = async (requestId: string) => {
    try {
      await removeFromFavorites(requestId);
      message.success('已取消收藏');
      // Optimistic update
      setFavorites(prev => prev.filter(f => f.request_id !== requestId));
    } catch (error) {
      presentRequestError(error, message.error, '取消收藏失败');
    }
  };

  const columns: ColumnsType<FavoriteItem> = [
    {
      title: '问题',
      dataIndex: 'question',
      key: 'question',
      ellipsis: true,
      render: (text: string) => <span style={{ fontWeight: 500 }}>{text}</span>,
    },
    {
      title: '组织',
      dataIndex: 'org_name',
      key: 'org_name',
      width: 150,
    },
    {
      title: '图表类型',
      dataIndex: 'recommended_chart',
      key: 'recommended_chart',
      width: 120,
      render: (type: string) => {
        return <Tag color={chartColors[type] || 'default'}>{type}</Tag>;
      },
    },
    {
      title: '收藏时间',
      dataIndex: 'favorited_at',
      key: 'favorited_at',
      width: 180,
      render: (text: string) => formatDate(text),
    },
    {
      title: '最近运行',
      dataIndex: 'last_run_at',
      key: 'last_run_at',
      width: 180,
      render: (text: string) => (text ? formatDate(text) : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 170,
      align: 'right',
      className: 'favorites-table__action-column',
      render: (_: unknown, record: FavoriteItem) => (
        <Space size={[8, 8]} wrap className="favorites-table__actions">
          <Button
            type="link"
            size="small"
            icon={<ReloadOutlined />}
            className="favorites-table__action-button favorites-table__action-button--primary"
            onClick={() => handleRerun(record)}
          >
            重新执行
          </Button>
          <Button
            type="text"
            size="small"
            danger
            icon={<DeleteOutlined />}
            className="favorites-table__action-button"
            onClick={() => handleRemove(record.request_id)}
          >
            取消收藏
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="favorites-page">
      <div className="favorites-page__hero">
        <div>
          <Title level={4} style={{ marginBottom: 8 }}>我的收藏</Title>
          <Paragraph style={{ marginBottom: 0 }}>收藏常用查询，方便快速访问。</Paragraph>
        </div>
        <Tag color="gold" className="favorites-page__count-tag">
          {favorites.length} 条收藏
        </Tag>
      </div>

      <Card className="favorites-page__card" styles={{ body: { padding: 20 } }}>
        {favorites.length === 0 ? (
          <Empty
            image={<StarOutlined style={{ fontSize: 48, color: '#ccc' }} />}
            description={
              <span style={{ color: '#999' }}>
                暂无收藏内容<br />
                在查询结果页点击 ☆ 可收藏常用问题
              </span>
            }
          />
        ) : (
          <Table
            className="favorites-table"
            columns={columns}
            dataSource={favorites}
            rowKey="request_id"
            pagination={false}
            loading={loading}
            scroll={{ x: 980 }}
            locale={{ emptyText: '暂无收藏的查询' }}
          />
        )}
      </Card>
    </div>
  );
};

export default FavoritesPage;
