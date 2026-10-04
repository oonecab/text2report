import { create } from 'zustand';
import type { UserInfo } from '@/types';

interface AuthState {
  user: UserInfo | null;
  isLoggedIn: boolean;
  initialized: boolean;
  loading: boolean;
  userId: number | null;
  role: 'ADMIN' | 'MANAGER' | 'USER' | null;
  orgGrants: Array<{
    orgId: number;
    orgName: string;
    scope: 'SELF' | 'CHILDREN' | 'ALL';
    isPrimary: boolean;
  }>;
  setAuth: (user: UserInfo) => void;
  clearAuth: () => void;
  setLoading: (loading: boolean) => void;
  setInitialized: (initialized: boolean) => void;
  isAdmin: () => boolean;
  isManagerOrAbove: () => boolean;
}

export const useAuthStore = create<AuthState>()((set, get) => ({
  user: null,
  isLoggedIn: false,
  initialized: false,
  loading: false,
  userId: null,
  role: null,
  orgGrants: [],

  setAuth: (user: UserInfo) => {
    set({
      user,
      isLoggedIn: true,
      initialized: true,
      loading: false,
      userId: user.userId != null ? Number(user.userId) : (user.id != null ? Number(user.id) : null),
      role: (String(user.role ?? '').toUpperCase() as AuthState['role']) || null,
      orgGrants: user.orgGrants ?? [],
    });
  },

  clearAuth: () => {
    set({
      user: null,
      isLoggedIn: false,
      initialized: true,
      loading: false,
      userId: null,
      role: null,
      orgGrants: [],
    });
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem('auth-storage');
    }
  },

  setLoading: (loading: boolean) => set({ loading }),
  setInitialized: (initialized: boolean) => set({ initialized }),

  isAdmin: () => {
    const { role } = get();
    return role === 'ADMIN';
  },

  isManagerOrAbove: () => {
    const { role } = get();
    return role === 'ADMIN' || role === 'MANAGER';
  },
}));
