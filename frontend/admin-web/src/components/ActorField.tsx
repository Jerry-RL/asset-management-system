import { useEffect, useMemo, useState } from 'react';
import { Select } from 'antd';
import { api } from '@/lib/api';
import { normalizeList } from '@/lib/org';

// ============================================================================
// 相对人字段（受控）。设计见 docs/superpowers/specs/2026-09-12-record-forms-design.md
// §4.4（口径）与 §6.5（组件）。
//
// 两种来源，由选项值的前缀区分（不用选项值的类型去猜 —— 员工 id 与姓名都可能长成
// "123"，靠形状判断会串台）：
//   u:<id>  → 系统内员工，值形态 { userId, name }，后端会用 sys_user.name 覆盖 name；
//   x:<姓名> → 外部人员，值形态 { name }，手填，服务端要求 name 非空。
//
// 直接作为 <Form.Item name="xxx"> 的子节点使用：
//   <Form.Item name="handoverUser" rules={[{ required: true }]}><ActorField .../></Form.Item>
//
// 依赖 `GET /system/users`，该端点需要 `org.user:view`。无此权限时下拉为空，
// 但仍可走外部人员分支 —— 降级是安全的，不会把已有值抹掉。
//
// 关键字搜索是**全局**的（不带公司范围），这是有意的：交接人可能在别的部门。
// 安全性由服务端保证 —— `UserService.page` 会 `applyCompanyScope` 按当前账号的
// 数据范围过滤，调用方拿不到范围外的员工。
// ============================================================================

/** 表单值形态：内员带 userId，外部人员只带 name */
export interface ActorValue {
  userId?: number;
  name: string;
}

interface ActorFieldProps {
  value?: ActorValue | null;
  onChange?: (value: ActorValue | null) => void;
  /** 部门范围：与 companyId 同时存在时以本项为准 */
  departmentId?: number | null;
  /** 公司范围：仅在没有 departmentId 时生效 */
  companyId?: number | null;
  placeholder?: string;
  disabled?: boolean;
}

interface EmployeeOption {
  id: number;
  name: string;
}

const EMPLOYEE_PREFIX = 'u:';
const EXTERNAL_PREFIX = 'x:';

/** 远程搜索防抖(ms)：太短会按每个字符打一次接口，太长会让「搜不到→记外部人员」变迟钝。 */
const SEARCH_DEBOUNCE_MS = 300;
const PAGE_SIZE = 50;

export function ActorField({
  value,
  onChange,
  departmentId,
  companyId,
  placeholder = '搜索系统内员工，或直接输入外部人员姓名',
  disabled,
}: ActorFieldProps) {
  const [employees, setEmployees] = useState<EmployeeOption[]>([]);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);

  // 远程搜索：部门优先，其次公司；两者都没有时不做请求（避免把全量用户拉下来）。
  // keyword 走防抖；清空 keyword 时回落成「浏览范围内的员工」。
  useEffect(() => {
    const scope = departmentId
      ? `departmentId=${departmentId}`
      : companyId
        ? `companyId=${companyId}`
        : null;
    if (!scope && !keyword) {
      // 既没有范围也没有关键字：无从查起，保持空列表（外部人员分支仍可用）
      setEmployees([]);
      return;
    }
    let cancelled = false;
    const timer = setTimeout(() => {
      setLoading(true);
      const query = new URLSearchParams({ page: '1', pageSize: String(PAGE_SIZE) });
      if (departmentId) query.set('departmentId', String(departmentId));
      else if (companyId) query.set('companyId', String(companyId));
      if (keyword) query.set('keyword', keyword);
      api
        .get<unknown>(`/system/users?${query.toString()}`)
        .then((raw) => {
          if (cancelled) return;
          setEmployees(
            normalizeList<EmployeeOption>(raw).map((item) => ({
              id: Number(item.id),
              name: String(item.name ?? item.id),
            })),
          );
        })
        .catch(() => {
          // 403（缺 org.user:view）或网络异常：退回空列表，外部人员仍可手填
          if (!cancelled) setEmployees([]);
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [departmentId, companyId, keyword]);

  const trimmedKeyword = keyword.trim();
  const currentName = value?.name ?? '';
  const currentEmployeeId = value?.userId ?? null;

  /** 选项值 → 前缀 + 载荷。当前值始终补进选项，避免回显时显示成裸前缀。 */
  const selectOptions = useMemo(() => {
    const list = employees.map((employee) => ({
      value: `${EMPLOYEE_PREFIX}${employee.id}`,
      label: employee.name,
    }));
    const values = new Set(list.map((option) => option.value));

    // 已有值兜底：员工不在当前结果集里（被关键字过滤掉了）也要能显示姓名
    if (currentEmployeeId != null) {
      const optionValue = `${EMPLOYEE_PREFIX}${currentEmployeeId}`;
      if (!values.has(optionValue)) {
        list.unshift({ value: optionValue, label: currentName || String(currentEmployeeId) });
        values.add(optionValue);
      }
    } else if (currentName) {
      // 外部人员：值本身就是姓名
      const optionValue = `${EXTERNAL_PREFIX}${currentName}`;
      if (!values.has(optionValue)) {
        list.unshift({ value: optionValue, label: `使用外部人员：${currentName}` });
        values.add(optionValue);
      }
    }

    // 设计 §6.5「无匹配时提供『使用外部人员：<输入值>』」：把输入的姓名做成一个可选项
    if (trimmedKeyword) {
      const optionValue = `${EXTERNAL_PREFIX}${trimmedKeyword}`;
      const hitEmployee = employees.some((employee) => employee.name === trimmedKeyword);
      if (!hitEmployee && !values.has(optionValue)) {
        list.unshift({ value: optionValue, label: `使用外部人员：${trimmedKeyword}` });
      }
    }
    return list;
  }, [employees, currentEmployeeId, currentName, trimmedKeyword]);

  const selectedValue =
    currentEmployeeId != null
      ? `${EMPLOYEE_PREFIX}${currentEmployeeId}`
      : currentName
        ? `${EXTERNAL_PREFIX}${currentName}`
        : undefined;

  const handleChange = (raw: string | undefined) => {
    setKeyword('');
    if (!raw) {
      onChange?.(null);
      return;
    }
    if (raw.startsWith(EMPLOYEE_PREFIX)) {
      const id = Number(raw.slice(EMPLOYEE_PREFIX.length));
      const employee = employees.find((item) => item.id === id);
      // 走兜底选项选中时 employees 里未必有它，此时保留当前姓名快照
      onChange?.(
        employee
          ? { userId: employee.id, name: employee.name }
          : { userId: id, name: currentName || String(id) },
      );
      return;
    }
    if (raw.startsWith(EXTERNAL_PREFIX)) {
      const name = raw.slice(EXTERNAL_PREFIX.length).trim();
      if (!name) {
        onChange?.(null);
        return;
      }
      // 外部人员不送 userId：后端据此跳过姓名快照覆盖（设计 §4.4）
      onChange?.({ name });
      return;
    }
    // 理论上不可达（选项值一律带前缀）；保留为外部人员姓名而不是静默丢弃
    onChange?.({ name: raw });
  };

  return (
    <Select
      showSearch
      allowClear
      disabled={disabled}
      className="w-full"
      placeholder={placeholder}
      value={selectedValue}
      options={selectOptions}
      loading={loading}
      // 过滤交给后端 keyword，前端不再过滤，否则「外部人员」选项会被滤掉
      filterOption={false}
      onSearch={setKeyword}
      onChange={handleChange}
      // 有关键字时下拉里必然有「使用外部人员」那一项，所以这里只在真正空集时出现
      notFoundContent={loading ? '搜索中…' : '无可选员工'}
    />
  );
}
