import { create } from 'zustand';
import type { QueryResponse, QueryHistory } from '@/types';
import { submitQuery as submitQueryApi } from '@/services/queryApi';

interface QueryState {
  currentResult: QueryResponse | null;
  history: QueryHistory[];
  loading: boolean;
  error: string | null;
  submitQuery: (question: string, orgId: string | number) => Promise<void>;
  setResult: (response: QueryResponse) => void;
  clearResult: () => void;
  addToHistory: (item: QueryHistory) => void;
  setHistory: (items: QueryHistory[]) => void;
  setLoading: (loading: boolean) => void;
  setFavoriteStatus: (favorited: boolean) => void;
  clearError: () => void;
}

export const useQueryStore = create<QueryState>((set) => ({
  currentResult: null,
  history: [],
  loading: false,
  error: null,

  submitQuery: async (question: string, orgId: string | number) => {
    set({ loading: true, error: null });
    try {
      const response = await submitQueryApi({ question, orgId });
      set({ currentResult: response, loading: false });
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : '查询失败，请重试';
      set({ error: errorMessage, loading: false });
    }
  },

  setResult: (response: QueryResponse) => {
    set({ currentResult: response });
  },

  clearResult: () => {
    set({ currentResult: null });
  },

  addToHistory: (item: QueryHistory) => {
    set((state) => ({
      history: [item, ...state.history],
    }));
  },

  setHistory: (items: QueryHistory[]) => {
    set({ history: items });
  },

  setLoading: (loading: boolean) => {
    set({ loading });
  },

  setFavoriteStatus: (favorited: boolean) => {
    set((state) => ({
      currentResult: state.currentResult
        ? { ...state.currentResult, is_favorited: favorited }
        : null,
    }));
  },

  clearError: () => {
    set({ error: null });
  },
}));
