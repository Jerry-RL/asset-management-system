import type { ApiMenuNode } from './menu';

/**
 * 角色权限页的纯推导逻辑（设计 4.4）。
 *
 * <p>抽出来的原因：这里有两处**语义容易搞反**的规则 —— 数据范围是「勾选即排除」
 * （与全站其他树相反），且排除要连同整棵下级子树；矩阵的目录行是子菜单的聚合半选态。
 * 放在组件里只能靠肉眼评审，抽成纯函数后可以直接断言。
 */

export interface CompanyLike {
  id: number;
  name: string;
  parentId?: number | null;
}

export interface CompanyIndex<T extends CompanyLike> {
  roots: T[];
  childrenOf: (parentId: number) => T[];
  parentOf: (id: number) => T | undefined;
}

/**
 * 按 `parentId` 建索引。
 *
 * <p>父节点不在本批数据里（或为 null）时按根节点处理，避免脏数据导致整棵子树消失 ——
 * 与后端 `MenuService.buildTree` 的口径一致。
 */
export function buildCompanyIndex<T extends CompanyLike>(companies: readonly T[]): CompanyIndex<T> {
  const ids = new Set(companies.map((c) => c.id));
  const map = new Map<number | null, T[]>();
  const byId = new Map<number, T>();
  for (const company of companies) {
    byId.set(company.id, company);
    const key = company.parentId != null && ids.has(company.parentId) ? company.parentId : null;
    const list = map.get(key) ?? [];
    list.push(company);
    map.set(key, list);
  }
  return {
    roots: map.get(null) ?? [],
    childrenOf: (parentId: number) => map.get(parentId) ?? [],
    parentOf: (id: number) => {
      const parentId = byId.get(id)?.parentId;
      return parentId != null && ids.has(parentId) ? byId.get(parentId) : undefined;
    },
  };
}

/**
 * 展开「继承排除」：被显式排除的公司的下级中，**自身未被显式排除**的那些。
 *
 * <p>刻意排除显式项，让三种状态互斥且完整：
 * <ul>
 *   <li>显式排除 —— 在 `excluded` 里，可取消；</li>
 *   <li>继承排除 —— 在本函数的返回值里，置灰；</li>
 *   <li>基线内 —— 两者都不在。</li>
 * </ul>
 * 若把显式项也算进「继承」，子节点会既被置灰又无法取消：而它**必须保持可取消**，
 * 否则「排除父公司 → 再排除子 C → 取消父公司」这条常见操作会把 C 的显式排除一并丢掉，
 * 让该角色悄悄重新获得 C 的访问权（安全侧的静默放宽）。
 *
 * <p>环形 `parentId`（脏数据）会让树成环：`visited` 去重必不可少，否则遍历会无限循环，
 * 页面卡死在加载态且没有任何报错。
 */
export function collectInheritedExcluded<T extends CompanyLike>(
  roots: readonly T[],
  childrenOf: (parentId: number) => T[],
  excluded: readonly number[],
): Set<number> {
  const excludedSet = new Set(excluded);
  const result = new Set<number>();
  const visited = new Set<number>();

  const walk = (nodes: readonly T[]) => {
    for (const node of nodes) {
      if (visited.has(node.id)) continue;
      visited.add(node.id);
      // 只要祖先被排除，整棵子树（除显式项外）都是继承排除
      const stack: Array<{ node: T; inherited: boolean }> = (
        childrenOf(node.id) ?? []
      ).map((child) => ({ node: child, inherited: excludedSet.has(node.id) }));
      while (stack.length) {
        const { node: current, inherited } = stack.pop() as { node: T; inherited: boolean };
        if (visited.has(current.id)) continue;
        visited.add(current.id);
        if (inherited && !excludedSet.has(current.id)) result.add(current.id);
        for (const child of childrenOf(current.id) ?? []) {
          stack.push({ node: child, inherited: inherited || excludedSet.has(current.id) });
        }
      }
    }
  };

  walk(roots);
  return result;
}

/**
 * 该公司的祖先里是否有「显式排除」项。
 *
 * <p>用于给「显式排除但当前已被上级覆盖」的行加提示：它此刻是冗余的，
 * 但一旦取消上级排除就会生效，所以**不能**像继承排除那样置灰。
 */
export function underExcludedAncestor<T extends CompanyLike>(
  id: number,
  parentOf: (id: number) => T | undefined,
  excluded: readonly number[],
): boolean {
  const excludedSet = new Set(excluded);
  const seen = new Set<number>([id]);
  let current = parentOf(id);
  while (current) {
    if (excludedSet.has(current.id)) return true;
    if (seen.has(current.id)) return false; // 环形脏数据兜底
    seen.add(current.id);
    current = parentOf(current.id);
  }
  return false;
}

export interface ActionState {
  checked: boolean;
  indeterminate: boolean;
}

/**
 * 目录行在某动作上的聚合态（设计 4.4「目录行支持全选 / 半选」）。
 *
 * <p>没有子菜单时返回「未选中且非半选」：此时复选框会被置灰，
 * 若返回半选态会显示成一个点，让管理员以为有隐藏的授权。
 */
export function dirActionState(
  children: readonly { id: number }[],
  actionsOf: (menuId: number) => readonly string[],
  action: string,
): ActionState {
  if (children.length === 0) return { checked: false, indeterminate: false };
  const hit = children.filter((c) => actionsOf(c.id).includes(action)).length;
  return { checked: hit === children.length, indeterminate: hit > 0 && hit < children.length };
}

/**
 * 草稿 → 请求体。
 *
 * <p>过滤空数组：后端是「先删该角色全部授权行、再插入提交项」，
 * 提交 `{menuId, actions: []}` 不写入任何行，但会让请求体带上全部菜单，
 * 徒增体积且掩盖「哪些菜单真的被授权」。
 */
export function buildPermissionItems(
  draft: Readonly<Record<number, readonly string[]>>,
): Array<{ menuId: number; actions: string[] }> {
  return Object.entries(draft)
    .filter(([, actions]) => actions.length > 0)
    .map(([menuId, actions]) => ({ menuId: Number(menuId), actions: [...actions] }));
}

/** 目录行的某个动作是否在其全部子菜单上都被勾选（用于「选择本列」批量的幂等判断）。 */
export const dirFullyChecked = (
  dir: ApiMenuNode,
  actionsOf: (menuId: number) => readonly string[],
  action: string,
): boolean => {
  const children = dir.children ?? [];
  return children.length > 0 && children.every((c) => actionsOf(c.id).includes(action));
};
