import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Form, Input, Button, Card, App } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/store/useAuthStore';
import { getCsrfToken, login as loginApi } from '@/services/authApi';
import type { LoginRequest } from '@/types';
import InsightBridgeIcon from '@/components/common/InsightBridgeIcon';
import { isAppApiError, presentRequestError } from '@/utils/apiError';

const LoginPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const { setAuth } = useAuthStore();
  const { message, modal } = App.useApp();

  const showAlreadyLoggedInModal = () => {
    modal.warning({
      title: '账户已登录',
      content: '该账户当前已在其他浏览器或设备登录，请先退出原来的账户后再重新登录。',
      okText: '我知道了',
      centered: true,
    });
  };

  const onFinish = async (values: LoginRequest) => {
    setLoading(true);
    try {
      await getCsrfToken();
      const response = await loginApi(values);
      // Spring Security rotates the CSRF token after authentication succeeds.
      // Refresh it immediately so the first authenticated write action can pass.
      await getCsrfToken();
      setAuth(response.user);
      message.success('登录成功');

      // Login API already returns the authenticated user; use it directly to avoid
      // racing a follow-up /auth/me request before the new token is stored.
      const normalizedRole = String(response.user.role).toUpperCase();
      if (normalizedRole === 'ADMIN' || normalizedRole === 'MANAGER') {
        navigate('/admin');
      } else {
        navigate('/query');
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : '登录失败，请检查用户名和密码';
      if (errorMessage.includes('账号已在其他设备登录')) {
        showAlreadyLoggedInModal();
        return;
      }
      if (isAppApiError(err) && err.handled) {
        return;
      }
      presentRequestError(err, message.error, '登录失败，请检查用户名和密码');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-page__glow login-page__glow--blue" />
      <div className="login-page__glow login-page__glow--gold" />
      <Card
        className="login-card"
        styles={{
          body: {
            padding: 36,
          },
        }}
      >
        <div className="login-card__header">
          <div className="login-card__brand-badge">
            <span className="login-card__brand-icon">
              <InsightBridgeIcon size={18} />
            </span>
            <span className="login-card__brand-text">InsightBridge</span>
          </div>

          <h1 className="login-card__title">
            <span className="login-card__title-brand">InsightBridge</span>
          </h1>

          <p className="login-card__subtitle">
            Elegant analytics access for governed natural-language reporting
          </p>
        </div>

        <Form
          className="login-form"
          name="login"
          onFinish={onFinish}
          autoComplete="off"
          layout="vertical"
        >
          <Form.Item
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input
              prefix={<UserOutlined />}
              placeholder="用户名"
              size="large"
            />
          </Form.Item>

          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="密码"
              size="large"
            />
          </Form.Item>

          <Form.Item>
            <Button
              className="login-form__submit"
              type="primary"
              htmlType="submit"
              loading={loading}
              block
              size="large"
            >
              登录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
};

export default LoginPage;
