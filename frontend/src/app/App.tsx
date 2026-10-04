import { RouterProvider } from 'react-router-dom';
import { ConfigProvider, App as AntdApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useEffect } from 'react';
import { router } from './routes';
import { getCsrfToken, getCurrentUser } from '@/services/authApi';
import { setGlobalMessage } from '@/services/request';
import { useAuthStore } from '@/store/useAuthStore';
import type { UserInfo } from '@/types';

let sessionBootstrapPromise: Promise<UserInfo | null> | null = null;

const bootstrapSession = async (): Promise<UserInfo | null> => {
  if (!sessionBootstrapPromise) {
    sessionBootstrapPromise = (async () => {
      await getCsrfToken({ silent: true });
      return getCurrentUser({ silent: true });
    })().catch(() => null);
  }

  return sessionBootstrapPromise;
};

const AppContent: React.FC = () => {
  const { message } = AntdApp.useApp();
  const { setAuth, clearAuth, setLoading, setInitialized } = useAuthStore();

  useEffect(() => {
    // 将 message 实例注入到全局请求拦截器
    setGlobalMessage(message);
  }, [message]);

  useEffect(() => {
    let cancelled = false;

    const bootstrap = async () => {
      setLoading(true);
      const user = await bootstrapSession();
      if (!cancelled) {
        if (user) {
          setAuth(user);
        } else {
          clearAuth();
        }
        setLoading(false);
        setInitialized(true);
      }
    };

    void bootstrap();

    return () => {
      cancelled = true;
    };
  }, [clearAuth, setAuth, setInitialized, setLoading]);

  return <RouterProvider router={router} />;
};

const App: React.FC = () => {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#1677ff',
          colorBgLayout: '#f5f7fa',
          colorBorderSecondary: '#f0f0f0',
          borderRadius: 8,
          fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
        },
        components: {
          Layout: {
            bodyBg: '#f5f7fa',
            headerBg: '#ffffff',
            siderBg: '#ffffff',
          },
        },
      }}
    >
      <AntdApp>
        <AppContent />
      </AntdApp>
    </ConfigProvider>
  );
};

export default App;
