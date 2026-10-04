/** 组织机构节点 */
export interface OrgNode {
  id: string | number;
  name: string;
  parentId: string | number | null; // null 表示根节点（母公司）
  level: number; // 1=母公司, 2=子公司, 3=部门
  code?: string;
  isActive: boolean;
  children?: OrgNode[];
}
