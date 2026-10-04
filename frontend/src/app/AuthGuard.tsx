import { Navigate, Outlet } from 'react-router-dom';
import { Spin } from 'antd';
import { useAuthStore } from '@/store/useAuthStore';

interface AuthGuardProps {
  requireAdmin?: boolean;
  requireManager?: boolean;
}

export const AuthGuard: React.FC<AuthGuardProps> = ({ requireAdmin = false, requireManager = false }) => {
  const { isLoggedIn, isAdmin, isManagerOrAbove, initialized, loading } = useAuthStore();

  if (!initialized || loading) {
    return <Spin fullscreen tip="正在恢复登录状态..." />;
  }

  // 未登录 → 跳转登录页
  if (!isLoggedIn) {
    return <Navigate to="/login" replace />;
  }

  // 需要 admin 角色但当前用户不是 admin → 跳转查询页
  if (requireAdmin && !isAdmin()) {
    return <Navigate to="/query" replace />;
  }

  if (requireManager && !isManagerOrAbove()) {
    return <Navigate to="/query" replace />;
  }

  return <Outlet />;
};
