import type { OrgNode } from '@/types';

export const ORG_TREE_DATA: OrgNode[] = [
  {
    id: 'org-root',
    name: '华夏集团',
    level: 1,
    parentId: null,
    isActive: true,
    children: [
      {
        id: 'org-sub-1',
        name: '华夏贸易有限公司',
        level: 2,
        parentId: 'org-root',
        isActive: true,
        children: [
          { id: 'org-dept-1', name: '销售一部', level: 3, parentId: 'org-sub-1', isActive: true },
          { id: 'org-dept-2', name: '销售二部', level: 3, parentId: 'org-sub-1', isActive: true },
          { id: 'org-dept-3', name: '市场部', level: 3, parentId: 'org-sub-1', isActive: true },
        ],
      },
      {
        id: 'org-sub-2',
        name: '华夏科技有限公司',
        level: 2,
        parentId: 'org-root',
        isActive: true,
        children: [
          { id: 'org-dept-4', name: '研发部', level: 3, parentId: 'org-sub-2', isActive: true },
          { id: 'org-dept-5', name: '产品部', level: 3, parentId: 'org-sub-2', isActive: true },
          { id: 'org-dept-6', name: '运维部', level: 3, parentId: 'org-sub-2', isActive: true },
        ],
      },
      {
        id: 'org-sub-3',
        name: '华夏服务有限公司',
        level: 2,
        parentId: 'org-root',
        isActive: true,
        children: [
          { id: 'org-dept-7', name: '客服部', level: 3, parentId: 'org-sub-3', isActive: true },
          { id: 'org-dept-8', name: '人事部', level: 3, parentId: 'org-sub-3', isActive: true },
          { id: 'org-dept-9', name: '财务部', level: 3, parentId: 'org-sub-3', isActive: true },
        ],
      },
    ],
  },
];

// In-memory storage for mutations
export const orgStorage: OrgNode[] = JSON.parse(JSON.stringify(ORG_TREE_DATA));
