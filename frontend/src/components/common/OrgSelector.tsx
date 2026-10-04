import { TreeSelect } from 'antd';
import type { TreeSelectProps } from 'antd';
import { useOrgStore } from '@/store/useOrgStore';
import type { OrgNode } from '@/types';

export const OrgSelector: React.FC = () => {
  const { orgTree, currentOrg, setCurrentOrg } = useOrgStore();

  const treeData = orgTree.map((node) => buildTreeData(node));

  function buildTreeData(node: OrgNode): { value: string | number; label: string; children?: ReturnType<typeof buildTreeData>[] } {
    const result = {
      value: node.id,
      label: node.name,
    };

    if (node.children && node.children.length > 0) {
      return {
        ...result,
        children: node.children.map(child => buildTreeData(child)),
      };
    }

    return result;
  }

  const findNode = (nodes: OrgNode[], id: string | number): OrgNode | null => {
    for (const node of nodes) {
      if (String(node.id) === String(id)) {
        return node;
      }
      if (node.children?.length) {
        const found = findNode(node.children, id);
        if (found) return found;
      }
    }
    return null;
  };

  const onChange: TreeSelectProps<string | number>['onChange'] = (value) => {
    if (value) {
      const node = findNode(orgTree, value);
      if (node) {
        setCurrentOrg(node);
      }
    } else {
      setCurrentOrg(null);
    }
  };

  return (
    <TreeSelect
      style={{ width: 200 }}
      value={currentOrg?.id}
      onChange={onChange}
      treeData={treeData}
      placeholder="选择组织范围"
      allowClear
      treeDefaultExpandAll
      showSearch
    />
  );
};
