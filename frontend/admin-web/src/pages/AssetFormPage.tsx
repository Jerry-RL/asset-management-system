import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Empty,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Spin,
  Steps,
  TreeSelect,
  message,
} from 'antd';
import type { FormInstance } from 'antd/es/form';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { api } from '@/lib/api';
import { assetScopeLock } from '@/lib/assetScopeLock';
import { ImageUploadField, type ImageValue } from '@/components/ImageUploadField';
import { RecordSheetSections } from '@/components/RecordSheetSections';
import { useDictOptions, useRemoteOptions, type SelectOption } from '@/lib/dict';
import * as L from '@/lib/labels';
import { buildCompanyTree, loadCompanies, type CompanyOption } from '@/lib/org';
import { useBackNavigate } from '@/lib/navigation';
import { usePerm } from '@/lib/perm';
import { floorLabel, useProjectZoneFloors } from '@/lib/projectZoneFloors';
import { loadOwnerSheet, saveOwnerSheet, type RecordSheetPayload } from '@/lib/recordSheet';

// ============================================================================
// 资产新增/修改：按业务分组表单
//   归属信息：产权公司 / 资产公司 / 项目 / 分区（逐级联动）
//   基本信息：名称、编号、坐落、分区楼层、资产面积、租赁面积
//   资产属性：全部取自「系统字典 → 资产管理字典」，字典项可动态补充
//   管理信息：登记入库时间、原值(万元)、责任部门 / 责任人（按资产公司级联）
//   计量与图片：水表号、电表号、资产图片（缩略图 + 可预览）
// ============================================================================

/** 字典编码 → 下拉选项（「不够可动态补充字典项」：字典维护后表单自动同步） */
const DICT_FIELDS: { name: string; label: string; code: string }[] = [
  { name: 'assetType', label: '资产类型', code: 'asset_type' },
  { name: 'partialLeaseStatus', label: '部分租赁状态', code: 'partial_lease_status' },
  { name: 'assetNature', label: '资产性质', code: 'asset_nature' },
  { name: 'houseType', label: '资产房型', code: 'asset_house_type' },
  { name: 'usageType', label: '资产用途', code: 'asset_usage' },
  { name: 'sourceType', label: '资产来源', code: 'asset_source' },
  { name: 'ownershipType', label: '资产权属', code: 'asset_ownership' },
  { name: 'buildingPlan', label: '建筑规划', code: 'building_plan' },
  { name: 'structureType', label: '建筑结构', code: 'building_structure' },
];

/**
 * 上级变更时清空下级已选值，避免残留上一级的选择（如换了资产公司却留着旧项目）。
 * 首次挂载与编辑回填不清空。
 */
const useCascadeReset = (form: FormInstance, source: string, dependents: string[]) => {
  const value = Form.useWatch(source, form);
  const previousRef = useRef<unknown>(undefined);
  const key = dependents.join(',');
  useEffect(() => {
    const names = key ? key.split(',') : [];
    const previous = previousRef.current;
    previousRef.current = value;
    if (previous === undefined || previous === value) return;
    names.forEach((name) => form.setFieldValue(name, undefined));
  }, [value, key, form]);
};

/** URL 数字参数解析：缺失 / 非法 / 非正数一律视为「未提供」，避免拼出 ?projectId=NaN */
const toPositiveNumber = (raw: string | null): number | undefined => {
  if (!raw) return undefined;
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? value : undefined;
};

/**
 * URL 楼层号解析：**允许 0 与负数**（地下层 B1 = -1），只挡非整数。
 *
 * <p>不能复用上面的 `toPositiveNumber`：那会把地下层参数整条丢掉，
 * 表现为「从 B1 楼层 Tab 点新增资产，楼层栏却是空的」。
 */
const toFloorNo = (raw: string | null): number | undefined => {
  if (!raw || !/^-?\d+$/.test(raw)) return undefined;
  const value = Number(raw);
  return Number.isSafeInteger(value) ? value : undefined;
};

export function AssetFormPage() {
  const navigate = useNavigate();
  const { id } = useParams();
  const isEdit = Boolean(id);
  const goBack = useBackNavigate('/assets');

  const [searchParams] = useSearchParams();
  /**
   * 「项目分区管理」页跳转过来时带的归属预设。
   * 编辑态**不使用**它：归属一律以 `/assets/{id}` 的返回值为准，两处都写同一字段会产生竞争。
   */
  const presetProjectId = toPositiveNumber(searchParams.get('projectId'));
  const presetZoneId = toPositiveNumber(searchParams.get('zoneId'));
  /**
   * 楼层预设（V50）：从「项目分区管理」页的楼层 Tab 点「新增资产」时带过来，
   * 直接落在该层。同样只在新增态生效。
   */
  const presetFloorNo = toFloorNo(searchParams.get('floorNo'));
  /**
   * 归属锁定：判据集中在 `lib/assetScopeLock.ts`（配真值表测试），这里只取结果。
   *
   * <p>`lockScope` 是**入口**标志，不是「逐字段锁定」的开关：它锁定的是「进入表单时就已经
   * 确定下来的归属」。项目在进入时必然已定（本入口从某个项目进来），分区却未必 ——
   * 「全部分区」Tab 下没有分区可锁。所以分区用的是 {@link AssetScopeLock.zone} 而不是
   * `lockScope`：直接把入口标志用在分区上会让「全部分区」拿到一个「灰化且为空」的分区框，
   * 既选不了，又因为没有 required 校验而能提交出 `zone_id = null` 的资产
   * （该资产在任何分区 Tab 下都看不见）。
   *
   * <p>这只是**入口侧的体验约束**，不是安全边界：服务端 `validateReferences`
   * （`AssetService:1064-1076`，创建/更新都会调用）会独立校验「项目、责任部门属于所选资产公司」
   * 与「分区属于所选项目」，不一致一律 400（如「所选项目不属于该资产公司」）。
   */
  const lockScope = searchParams.get('lockScope') === '1';

  /** 新增时预填归属；编辑态只保留 assetType，归属交给接口返回值 */
  const initialValues = isEdit
    ? { assetType: 'property' }
    : {
        assetType: 'property',
        projectId: presetProjectId,
        zoneId: presetZoneId,
        floorNo: presetFloorNo,
      };

  const [form] = Form.useForm();

  /**
   * 锁定态下由项目反查资产公司。
   *
   * <p>项目下拉的选项依赖资产公司（`/projects?companyId=`），且资产公司本身是必填项 ——
   * 不预填就等于让用户手选，而手选会触发级联清空刚锁住的项目/分区（详见设计 §6.7）。
   */
  const [lockedCompanyId, setLockedCompanyId] = useState<number | undefined>(undefined);

  useEffect(() => {
    // 只在「新增 + 锁定 + 有项目」时反查；编辑态归属一律以 /assets/{id} 为准
    if (!lockScope || isEdit || presetProjectId == null) return;
    let cancelled = false;
    api
      .get<Record<string, unknown>>(`/projects/${presetProjectId}`)
      .then((project) => {
        if (cancelled) return;
        const companyId = Number(project.companyId);
        if (Number.isFinite(companyId) && companyId > 0) setLockedCompanyId(companyId);
      })
      .catch(() => {
        // 反查失败不阻断表单：用户仍可手选公司，只是失去锁定体验
        if (!cancelled) setLockedCompanyId(undefined);
      });
    return () => {
      cancelled = true;
    };
  }, [lockScope, isEdit, presetProjectId]);

  /** 公司反查到位后写入。首次写入不算「变更」，因此不会触发级联清空（见上） */
  useEffect(() => {
    if (lockedCompanyId == null) return;
    form.setFieldsValue({ assetCompanyId: lockedCompanyId });
  }, [lockedCompanyId, form]);
  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [loading, setLoading] = useState(isEdit);
  const [notFound, setNotFound] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [step, setStep] = useState(0);
  const [recordSheet, setRecordSheet] = useState<RecordSheetPayload | null>(null);
  const [sheetLoading, setSheetLoading] = useState(false);
  // 处置单同步需要 operation.disposal:create：无该权限时 saveOwnerSheet 必须跳过那次请求，
  // 否则「只改了接收信息」的保存也会 403（权限判定口径与 RecordSheetSections 一致）
  const can = usePerm();

  const companyTree = useMemo(() => buildCompanyTree(companies), [companies]);

  // ---- 四级联动：资产公司 → 项目 → 分区；资产公司 → 责任部门 → 责任人 ----
  const assetCompanyId = Form.useWatch('assetCompanyId', form);
  const watchProjectId = Form.useWatch('projectId', form);
  /**
   * 编辑态下记录回填的分区 id：分区是「进入时未必已确定」的归属，
   * 编辑一个本身无分区的资产时它为空 —— 那格空白不该被锁（见 {@link assetScopeLock}）。
   */
  const watchZoneId = Form.useWatch('zoneId', form);
  const watchDepartmentId = Form.useWatch('responsibleDepartmentId', form);
  /** 分区楼层（V50）：楼层号由「该项目的该分区」查出来，不再是手填数字 */
  const watchFloorNo = Form.useWatch('floorNo', form);

  /**
   * 楼层下拉选项：`GET /projects/{projectId}/zones/{zoneId}/floors`。
   *
   * <p>后端返回的是「已登记楼层 ∪ 资产实际用到的楼层号」的并集并**已按楼层号升序**
   * （`ProjectZoneFloorService#list`，按楼层号排即物理楼层顺序），所以这里不再排序 ——
   * 前端再排一次就是第二个排序来源，两边迟早会不一致。
   *
   * <p>与分区同一条级联链：未选分区时不下拉（`disabled`），换分区时 `useCascadeReset` 清空已选楼层。
   */
  const {
    floors,
    loading: floorsLoading,
    loadFailed: floorsFailed,
  } = useProjectZoneFloors(watchProjectId, watchZoneId);

  const floorOptions = useMemo(() => {
    /**
     * 换分区后 `useProjectZoneFloors` 会**保留上一个分区的列表**直到新响应返回（`loading` 期间）。
     * 这里在加载中把列表选项清空：宁可短暂不可选，也不能让上一个小区的楼层被选中 ——
     * `floorNo` 只是一个整数约定，后端不会校验它是否属于该分区，选错就会**存下来**。
     * 兜底项仍保留（见下），否则编辑态那几毫秒会显示裸数字。
     */
    const options: { value: number; label: string }[] = floorsLoading
      ? []
      : floors.map((floor) => ({ value: floor.floorNo, label: floorLabel(floor) }));
    /**
     * 历史数据兜底：已选楼层不在该分区的楼层列表里（该楼层记录被删、或资产数据早于 V50）时补一项。
     *
     * <p>不补的话下拉会显示**空白**而表单里其实存着值 —— 用户以为没填、保存后原值又被打回去，
     * 与「所见即所存」相悖。（同一手法见上面的 `sourceTypeSelectOptions`。）
     */
    if (watchFloorNo != null && !options.some((option) => option.value === watchFloorNo)) {
      options.push({ value: watchFloorNo, label: `${watchFloorNo}F` });
    }
    return options;
  }, [floors, floorsLoading, watchFloorNo]);

  /**
   * 楼层下拉的辅助说明。只在选好分区后才可能出现，三种情形各说各的：
   *
   * <pre>
   *   加载失败 → 说「加载失败」。**不能**说「暂无楼层」—— 那是把请求失败说成「这里本来就没有」，
   *              用户会跑去「项目分区管理」新建一个其实已经存在的楼层。
   *   加载中   → 不说（此时列表还没到，说什么都是猜）。
   *   列表为空 → 说「暂无楼层」并给出下一步去哪儿加。改成下拉之后手填已不可用，
   *              没有这句指引，用户面对的就是一个点不出任何东西的空下拉。
   * </pre>
   */
  const floorHint =
    watchZoneId == null || floorsLoading
      ? undefined
      : floorsFailed
        ? '楼层加载失败，可重新选择分区或刷新页面重试'
        : floors.length === 0
          ? '该分区暂无楼层，可先到「项目分区管理」的楼层 Tab 新增'
          : undefined;

  // 所选项目的「项目属性」（project.type，口径同「项目属性」字典），用于「资产来源」级联
  const [projectType, setProjectType] = useState<string | undefined>(undefined);
  useEffect(() => {
    if (!watchProjectId) {
      setProjectType(undefined);
      return;
    }
    let cancelled = false;
    api
      .get<Record<string, unknown>>(`/projects/${watchProjectId}`)
      .then((project) => {
        if (cancelled) return;
        setProjectType(typeof project.type === 'string' ? project.type : undefined);
      })
      .catch(() => {
        if (!cancelled) setProjectType(undefined);
      });
    return () => {
      cancelled = true;
    };
  }, [watchProjectId]);

  const projectOptions = useRemoteOptions(
    assetCompanyId ? `/projects?page=1&pageSize=200&companyId=${assetCompanyId}` : null,
  );
  const zoneOptions = useRemoteOptions(watchProjectId ? `/projects/${watchProjectId}/zones` : null);
  const departmentOptions = useRemoteOptions(
    assetCompanyId ? `/org/departments?companyId=${assetCompanyId}` : null,
  );
  const userOptions = useRemoteOptions(
    watchDepartmentId
      ? `/system/users?departmentId=${watchDepartmentId}&page=1&pageSize=200`
      : null,
  );

  useCascadeReset(form, 'assetCompanyId', [
    'projectId',
    'zoneId',
    'responsibleDepartmentId',
    'responsibleUserId',
  ]);
  // 项目变更 → 项目属性可能变化 → 「资产来源」可选范围变化，已选值一并清空
  useCascadeReset(form, 'projectId', ['zoneId', 'sourceType']);
  // 楼层号只在所属分区内才有意义（V50）：换分区必须清掉，否则会把 A 区的 3F 带进 B 区
  useCascadeReset(form, 'zoneId', ['floorNo']);
  useCascadeReset(form, 'responsibleDepartmentId', ['responsibleUserId']);

  /**
   * 三字段锁定判据（真值表与理由见 `lib/assetScopeLock.ts`）。
   *
   * <p>刻意**不**在 JSX 里逐字段写判据：这三个布尔量出过多次事故，而且都不报错 ——
   * 收敛到一处 + 配测试，才不会有人再顺手把 `lockScope` 直接用到分区上。
   *
   * <p>分区在**编辑态**取自记录（`watchZoneId`）而不是 URL：本入口的编辑链接只带 `lockScope`，
   * 记录本身就是分区的唯一真相。
   */
  const scopeLock = assetScopeLock({
    lockScope,
    isEdit,
    presetZoneId,
    recordZoneId: isEdit ? watchZoneId : undefined,
    lockedCompanyId,
  });

  // 字典下拉。「资产来源」按所选项目的项目属性级联（土地类 / 房产类可见项不同），
  // 规则在「系统管理 → 系统字典 → 项目属性 → 关联字典值」中维护。
  const assetTypeOptions = useDictOptions('asset_type');
  const partialLeaseStatusOptions = useDictOptions('partial_lease_status');
  const assetNatureOptions = useDictOptions('asset_nature');
  const houseTypeOptions = useDictOptions('asset_house_type');
  const usageTypeOptions = useDictOptions('asset_usage');
  const sourceTypeOptions = useDictOptions('asset_source', {
    parentCode: 'project_property',
    parentValue: projectType,
  });
  const ownershipTypeOptions = useDictOptions('asset_ownership');
  const buildingPlanOptions = useDictOptions('building_plan');
  const structureTypeOptions = useDictOptions('building_structure');

  // 历史数据兜底：当前「资产来源」不在级联可见项内时补一个选项，避免编辑时回显为空
  const sourceType = Form.useWatch('sourceType', form);
  const sourceTypeSelectOptions = useMemo(() => {
    const options = sourceTypeOptions.options;
    if (!sourceType || options.some((option) => option.value === sourceType)) return options;
    return [
      ...options,
      { value: sourceType, label: L.ASSET_SOURCE[String(sourceType)] ?? String(sourceType) },
    ];
  }, [sourceTypeOptions.options, sourceType]);

  const dictOptionMap: Record<string, { options: SelectOption[]; loading: boolean }> = {
    assetType: assetTypeOptions,
    partialLeaseStatus: partialLeaseStatusOptions,
    assetNature: assetNatureOptions,
    houseType: houseTypeOptions,
    usageType: usageTypeOptions,
    sourceType: { options: sourceTypeSelectOptions, loading: sourceTypeOptions.loading },
    ownershipType: ownershipTypeOptions,
    buildingPlan: buildingPlanOptions,
    structureType: structureTypeOptions,
  };

  useEffect(() => {
    void loadCompanies().then(setCompanies);
  }, []);

  // 编辑：回填资产信息（version 一并带回，PUT 需要乐观锁版本号）
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    const load = async () => {
      setLoading(true);
      try {
        const asset = await api.get<Record<string, unknown>>(`/assets/${id}`);
        if (cancelled) return;
        // 图片附件字段单独反解为上传组件值，避免额外 key 污染表单
        const url = typeof asset.imageUrl === 'string' ? asset.imageUrl : '';
        const fileId = typeof asset.imageFileId === 'number' ? asset.imageFileId : undefined;
        const assetFields: Record<string, unknown> = { ...asset };
        delete assetFields.imageUrl;
        delete assetFields.imageFileId;
        form.setFieldsValue({
          ...assetFields,
          // 后端返回 YYYY-MM-DD 字符串，DatePicker 需要 dayjs 对象
          registeredAt: asset.registeredAt ? dayjs(String(asset.registeredAt)) : undefined,
          image: url ? { url, fileId } : undefined,
        });
        setNotFound(false);
      } catch (e) {
        if (!cancelled) setNotFound(true);
        message.error(e instanceof Error ? e.message : '加载资产失败');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [id, form]);

  // 后续记录只在编辑态加载：新增态还没有 assetId，记录无处可挂（设计 §6.1）。
  // 注意它**不**进 antd Form：record-sheet 有自己的全量 diff 语义，塞进表单会让
  // validateFields 把它当普通字段校验一遍（见 handleSubmit 的注释）。
  useEffect(() => {
    if (!isEdit || !id) {
      setRecordSheet(null);
      return;
    }
    let cancelled = false;
    setSheetLoading(true);
    // 资产的处置单在 disposal_order 上（另一个端点），loadOwnerSheet 负责合并 —— 直接调
    // loadRecordSheet 会拿到永远为空的处置段，且不报错（见 lib/recordSheet.ts 注释）
    loadOwnerSheet('asset', Number(id))
      .then((sheet) => {
        if (cancelled) return;
        setRecordSheet({
          receives: sheet.receives,
          sourceInfo: sheet.sourceInfo,
          disposalRecords: sheet.disposalRecords,
          costRecords: sheet.costRecords,
          evaluations: sheet.evaluations,
        });
      })
      .catch((e) => {
        if (!cancelled) message.error(e instanceof Error ? e.message : '加载后续记录失败');
      })
      .finally(() => {
        if (!cancelled) setSheetLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isEdit, id]);

  /**
   * 校验失败时把用户送回第一个出错的步骤。
   *
   * <p>本页用 hidden（而非条件渲染）保持字段挂载，所以**所有**步骤的必填项都会参与
   * 校验，错误可能落在当前看不到的步骤上。用 `data-step` 反查而不是维护一张
   * 「字段 → 步骤」的静态表：资产属性那一步的字段由 {@link DICT_FIELDS} 循环生成，
   * 静态表必然会漂移。antd 会把 Form.Item 的 name 设为控件的 DOM id。
   */
  const jumpToErrorStep = (errorFields: { name: (string | number)[] }[]) => {
    const first = errorFields[0]?.name?.[0];
    if (typeof first !== 'string') return;
    const raw = document.getElementById(first)?.closest('[data-step]')?.getAttribute('data-step');
    const target = raw == null ? null : Number(raw);
    if (target == null || Number.isNaN(target)) {
      message.warning('有必填项未完成，请检查前面的步骤');
      return;
    }
    if (target !== step) {
      setStep(target);
      message.warning('有必填项未完成，已切换到对应步骤');
    }
  };

  const handleSubmit = async () => {
    try {
      const values = (await form.validateFields()) as Record<string, unknown> & {
        registeredAt?: Dayjs;
        image?: ImageValue | null;
      };
      // 图片值拆回 imageUrl / imageFileId 两个字段提交
      const { image, ...rest } = values;
      const payload = {
        ...rest,
        registeredAt: values.registeredAt ? values.registeredAt.format('YYYY-MM-DD') : null,
        imageUrl: image?.url ?? '',
        imageFileId: image?.fileId,
      };
      setSubmitting(true);
      if (isEdit) {
        // 顺序不能反：后续记录依赖主体已存在（设计 §5.4）
        await api.put(`/assets/${id}`, payload);
        try {
          if (recordSheet) {
            // 资产侧与台账侧的编排差异都在 saveOwnerSheet 内（处置单走另一个端点），
            // 返回值必须回写：新卡片的 id、附件名、流程状态都在里面
            const saved = await saveOwnerSheet('asset', Number(id), recordSheet, {
              canSyncDisposals: can('operation.disposal', 'create'),
            });
            setRecordSheet(saved);
          }
          message.success('保存成功');
        } catch (e) {
          // 「主体已保存、记录失败」的半成品状态必须让使用者知道，
          // 否则会以为整单回滚、重新编辑一遍（设计 §6.1）
          message.warning(
            `资产已保存，但后续记录保存失败：${e instanceof Error ? e.message : '未知错误'}，请重试`,
          );
          return;
        }
      } else {
        await api.post('/assets', payload);
        message.success('创建成功');
      }
      // 按来源返回：从资产台账进来回 /assets（与改造前一致），从「项目分区管理」进来
      // 回到带 projectId/zoneId 的原地（见 useBackNavigate 的 state.from 约定）
      goBack();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) {
        jumpToErrorStep((e as { errorFields?: { name: (string | number)[] }[] }).errorFields ?? []);
        return;
      }
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  if (isEdit && loading) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-24 text-gray-500">
        <Spin size="large" />
        <span>加载资产信息...</span>
      </div>
    );
  }

  if (isEdit && notFound) {
    return <Empty description="未找到资产" className="py-24" />;
  }

  return (
    <div className="space-y-4 min-w-0">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={goBack} aria-label="返回原页面">
            返回
          </Button>
          <h2 className="text-base font-semibold m-0">{isEdit ? '编辑资产' : '新增资产'}</h2>
        </Space>
      </div>

      <Form form={form} layout="vertical" initialValues={initialValues}>
        {/* 乐观锁版本号：编辑保存时随 PUT 回传，缺失会被判定为并发冲突 */}
        <Form.Item name="version" hidden>
          <InputNumber />
        </Form.Item>

        {/* 步骤条：独立卡片，内容区按步骤分组为多个卡片（与 ProjectFormPage 同款） */}
        <Card className="border border-[var(--ams-border)] mb-4">
          <Steps
            current={step}
            items={[
              { title: '归属与基本信息', description: '公司 / 项目 / 分区 / 名称 / 编号 / 面积' },
              {
                title: '资产属性与管理信息',
                description: '属性字典 / 登记入库 / 责任部门 / 计量与图片',
              },
              { title: '后续记录', description: '处置 / 接收 / 来源 / 成本 / 评估' },
            ]}
          />
        </Card>

        {/*
          分步用 hidden 而不是条件渲染：条件渲染会**卸载**非当前步的 Form.Item，
          而 validateFields() 只返回已注册字段 —— payload 会因此缺失其余两步的字段，
          编辑态的全量 PUT 会把它们清空。data-step 供校验失败时定位出错步骤。
        */}
        <div data-step={0} hidden={step !== 0}>
          <Card title="归属信息" className="border border-[var(--ams-border)] mb-4">
            <Row gutter={16}>
              <Col xs={24} md={12} lg={8}>
                <Form.Item name="propertyCompanyId" label="产权公司">
                  <TreeSelect
                    allowClear
                    showSearch
                    treeDefaultExpandAll
                    treeNodeFilterProp="key"
                    placeholder="请选择产权公司（可输入名称搜索）"
                    treeData={companyTree}
                    listHeight={320}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                <Form.Item
                  name="assetCompanyId"
                  label="资产公司"
                  rules={[{ required: true, message: '请选择资产公司' }]}
                  extra={
                    lockScope ? '由项目分区管理进入，归属已锁定' : '项目、责任部门均按资产公司级联'
                  }
                >
                  <TreeSelect
                    allowClear
                    showSearch
                    treeDefaultExpandAll
                    treeNodeFilterProp="key"
                    placeholder="请选择资产公司（可输入名称搜索）"
                    treeData={companyTree}
                    listHeight={320}
                    disabled={scopeLock.company || undefined}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                <Form.Item
                  name="projectId"
                  label="项目"
                  rules={[{ required: true, message: '请选择项目' }]}
                  extra={lockScope ? '由项目分区管理进入，归属已锁定' : undefined}
                >
                  <Select
                    allowClear
                    showSearch
                    optionFilterProp="label"
                    loading={projectOptions.loading}
                    disabled={scopeLock.project || !assetCompanyId}
                    placeholder={assetCompanyId ? '请选择项目（可搜索）' : '请先选择资产公司'}
                    options={projectOptions.options}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                {/*
                  锁定入口下分区必填：分区一旦可空，就能提交出 `zone_id = null` 的资产，
                  而它不在任何分区 Tab 下 —— 本页的存在意义就是「把资产归到某个分区里」。
                  台账页等不锁定的入口不受影响（那里的分区本就是可选项）。
                */}
                <Form.Item
                  name="zoneId"
                  label="分区"
                  rules={lockScope ? [{ required: true, message: '请选择分区' }] : undefined}
                  extra={scopeLock.zone ? '归属已锁定' : undefined}
                >
                  <Select
                    allowClear
                    showSearch
                    optionFilterProp="label"
                    loading={zoneOptions.loading}
                    disabled={scopeLock.zone || !watchProjectId}
                    placeholder={watchProjectId ? '请选择分区' : '请先选择项目'}
                    options={zoneOptions.options}
                  />
                </Form.Item>
              </Col>
            </Row>
          </Card>

          <Card title="基本信息" className="border border-[var(--ams-border)] mb-4">
            <Row gutter={16}>
              <Col xs={24} md={12} lg={8}>
                <Form.Item
                  name="name"
                  label="资产名称"
                  rules={[{ required: true, message: '请填写资产名称' }]}
                >
                  <Input placeholder="请输入资产名称" />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                <Form.Item
                  name="assetNo"
                  label="资产编号"
                  rules={[{ required: true, message: '请填写资产编号' }]}
                >
                  <Input placeholder="请输入资产编号" />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                {/*
                  楼层是下拉选择而不是手填数字：选项来自「该项目 + 该分区」，与「项目分区管理」
                  的楼层 Tab 同一数据源。手填可以写出一个该分区根本没有的楼层号，
                  那种资产在楼层 Tab 下既看不见也维护不了。
                */}
                <Form.Item name="floorNo" label="分区楼层" extra={floorHint}>
                  <Select
                    allowClear
                    showSearch
                    optionFilterProp="label"
                    loading={floorsLoading}
                    disabled={!watchZoneId}
                    placeholder={watchZoneId ? '请选择楼层' : '请先选择分区'}
                    options={floorOptions}
                  />
                </Form.Item>
              </Col>
              <Col xs={24}>
                <Form.Item name="address" label="资产坐落">
                  <Input placeholder="请输入具体地址" />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                <Form.Item name="area" label="资产面积">
                  <InputNumber
                    className="w-full"
                    min={0}
                    addonAfter="㎡"
                    placeholder="请输入资产面积"
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                <Form.Item name="leaseArea" label="租赁面积">
                  <InputNumber
                    className="w-full"
                    min={0}
                    addonAfter="㎡"
                    placeholder="请输入租赁面积"
                  />
                </Form.Item>
              </Col>
            </Row>
          </Card>
        </div>

        <div data-step={1} hidden={step !== 1}>
          <Card
            title="资产属性"
            className="border border-[var(--ams-border)] mb-4"
            extra={
              <Button size="small" onClick={() => navigate('/system/dict')}>
                维护字典
              </Button>
            }
          >
            <div className="text-xs text-gray-500 mb-3">
              选项取自「系统管理 → 系统字典 →
              资产管理字典」，字典项不足时可前往补充，表单会自动同步。
            </div>
            <Row gutter={16}>
              {DICT_FIELDS.map((field) => {
                const dict = dictOptionMap[field.name];
                return (
                  <Col key={field.name} xs={24} md={12} lg={8}>
                    <Form.Item name={field.name} label={field.label}>
                      <Select
                        allowClear
                        showSearch
                        optionFilterProp="label"
                        loading={dict?.loading}
                        placeholder={`请选择${field.label}`}
                        options={dict?.options ?? []}
                      />
                    </Form.Item>
                  </Col>
                );
              })}
            </Row>
          </Card>

          <Card title="管理信息" className="border border-[var(--ams-border)] mb-4">
            <Row gutter={16}>
              <Col xs={24} md={12} lg={8}>
                <Form.Item name="registeredAt" label="登记入库时间">
                  <DatePicker className="w-full" placeholder="请选择登记入库时间" />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                <Form.Item name="originalValue" label="原值">
                  <InputNumber
                    className="w-full"
                    min={0}
                    addonAfter="万元"
                    placeholder="请输入原值"
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                <Form.Item name="responsibleDepartmentId" label="责任部门">
                  <Select
                    allowClear
                    showSearch
                    optionFilterProp="label"
                    loading={departmentOptions.loading}
                    disabled={!assetCompanyId}
                    placeholder={assetCompanyId ? '请选择责任部门' : '请先选择资产公司'}
                    options={departmentOptions.options}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                <Form.Item name="responsibleUserId" label="责任人">
                  <Select
                    allowClear
                    showSearch
                    optionFilterProp="label"
                    loading={userOptions.loading}
                    disabled={!watchDepartmentId}
                    placeholder={watchDepartmentId ? '请选择责任人' : '请先选择责任部门'}
                    options={userOptions.options}
                  />
                </Form.Item>
              </Col>
            </Row>
          </Card>

          <Card title="计量与图片" className="border border-[var(--ams-border)] mb-4">
            <Row gutter={16}>
              <Col xs={24} md={12} lg={8}>
                <Form.Item name="waterMeterNo" label="水表号">
                  <Input placeholder="请输入水表号" />
                </Form.Item>
              </Col>
              <Col xs={24} md={12} lg={8}>
                <Form.Item name="electricMeterNo" label="电表号">
                  <Input placeholder="请输入电表号" />
                </Form.Item>
              </Col>
              <Col xs={24}>
                <Form.Item
                  name="image"
                  label="资产图片"
                  extra="支持 jpg/png，单张不超过 5MB，点击缩略图可预览"
                >
                  <ImageUploadField bizType="asset" previewTitle="资产图片" />
                </Form.Item>
              </Col>
            </Row>
          </Card>
        </div>

        <div data-step={2} hidden={step !== 2}>
          <Card
            title="后续记录"
            className="border border-[var(--ams-border)] mb-4"
            loading={sheetLoading}
          >
            {isEdit ? (
              <RecordSheetSections
                ownerType="asset"
                ownerId={Number(id)}
                companyId={assetCompanyId}
                disabled={false}
                value={recordSheet}
                onChange={setRecordSheet}
              />
            ) : (
              <Alert
                type="info"
                showIcon
                message="保存资产后可录入后续记录"
                description="处置、接收与来源信息都需要先有一个已保存的资产作为归属对象。请先提交本表单建好资产，再回到本步录入。"
              />
            )}
          </Card>
        </div>
      </Form>

      <div className="flex justify-end gap-2">
        <Button onClick={goBack}>取消</Button>
        {step > 0 && <Button onClick={() => setStep(step - 1)}>上一步</Button>}
        {step < 2 && (
          <Button type="primary" onClick={() => setStep(step + 1)}>
            下一步
          </Button>
        )}
        {step === 2 && (
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={submitting}
            onClick={() => void handleSubmit()}
          >
            {isEdit ? '保存' : '提交'}
          </Button>
        )}
      </div>
    </div>
  );
}
