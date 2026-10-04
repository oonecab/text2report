import { create } from 'zustand';
import type { OrgNode } from '@/types';
import { fetchMyOrgTree } from '@/services/orgApi';
import { useAuthStore } from '@/store/useAuthStore';

interface OrgState {
  orgTree: OrgNode[];
  currentOrg: OrgNode | null;
  loading: boolean;
  fetchOrgTree: () => Promise<void>;
  setCurrentOrg: (node: OrgNode | null) => void;
  getChildrenIds: (nodeId: string | number) => (string | number)[];
}

export const useOrgStore = create<OrgState>((set, get) => ({
  orgTree: [],
  currentOrg: null,
  loading: false,

  fetchOrgTree: async () => {
    set({ loading: true });
    try {
      const tree = await fetchMyOrgTree();
      const { currentOrg } = get();
      const { orgGrants } = useAuthStore.getState();

      const findNode = (nodes: OrgNode[], matcher: (node: OrgNode) => boolean): OrgNode | null => {
        for (const node of nodes) {
          if (matcher(node)) {
            return node;
          }
          if (node.children?.length) {
            const childMatch = findNode(node.children, matcher);
            if (childMatch) {
              return childMatch;
            }
          }
        }
        return null;
      };

      const primaryGrant = orgGrants.find((grant) => grant.isPrimary) ?? orgGrants[0];
      const nextCurrentOrg = currentOrg && findNode(tree, (node) => String(node.id) === String(currentOrg.id))
        ? currentOrg
        : primaryGrant
          ? findNode(tree, (node) => String(node.id) === String(primaryGrant.orgId))
          : tree[0] ?? null;

      set({ orgTree: tree, currentOrg: nextCurrentOrg, loading: false });
    } catch {
      set({ orgTree: [], currentOrg: null, loading: false });
    }
  },

  setCurrentOrg: (node: OrgNode | null) => {
    set({ currentOrg: node });
  },

  getChildrenIds: (nodeId: string | number) => {
    const { orgTree } = get();
    const ids: (string | number)[] = [];

    const collectIds = (nodes: OrgNode[]) => {
      for (const node of nodes) {
        if (node.id === nodeId || node.parentId === nodeId) {
          ids.push(node.id);
        }
        if (node.children) {
          collectIds(node.children);
        }
      }
    };

    collectIds(orgTree);
    return ids;
  },
}));
