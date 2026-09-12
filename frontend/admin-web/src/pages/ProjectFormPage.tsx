import { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Cascader,
  Col,
  Empty,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Spin,
  Steps,
  Table,
  TreeSelect,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  EnvironmentOutlined,
  PlusOutlined,
  ProfileOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { api } from '@/lib/api';
import { geocodeAddress } from '@/lib/amap';
import { useDictOptions } from '@/lib/dict';
import { PROJECT_STATUS, PROJECT_TYPE, PROJECT_TYPE_DICT_CODE } from '@/lib/labels';
import { useBackNavigate, currentPath } from '@/lib/navigation';
import { buildCompanyTree, loadCompanies, normalizeList } from '@/lib/org';
import { ImageUploadField, type ImageValue } from '@/components/ImageUploadField';
import type { ProjectZone } from '@/lib/projectZones';
import { RecordSheetSections } from '@/components/RecordSheetSections';
import {
  loadRecordSheet,
  recordSheetPath,
  saveRecordSheet,
  type RecordSheetPayload,
} from '@/lib/recordSheet';
import {
  loadRegions,
  regionCityHintOf,
  regionFieldsOf,
  regionPathOf,
  type RegionOption,
} from '@/lib/regions';
import { TableActions, actionsColumnWidth } from '@/components/TableActions';
import { PermissionGuard } from '@/lib/perm';

// ============================================================================
// 项目新增/编辑：分两步走
//   第一步 基本信息：名称、公司、类型、状态 / 地址定位 / 项目图片（缩略图+预览）
//   第二步 项目分区配置：名称、编码、排序、备注
//   分区面积不在此手工维护，由「该分区下资产面积合计」在展示时给出（只读）
// ============================================================================

interface CompanyOption {
  id: number;
  name: string;
  shortName?: string;
  parentId?: number | null;
  status?: number;
}

const STATUS_OPTIONS = Object.entries(PROJECT_STATUS).map(([value, label]) => ({
  value: Number(value),
  label,
}));

export function ProjectFormPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { id } = useParams();
  const isEdit = Boolean(id);
  const goBack = useBackNavigate('/projects');

  const [form] = Form.useForm();
  const [current, setCurrent] = useState(0);
  const [zones, setZones] = useState<ProjectZone[]>([]);
  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [regions, setRegions] = useState<RegionOption[]>([]);
  /** 编辑态加载到的项目原始数据，供省市区级联异步回填使用 */
  const [projectBase, setProjectBase] = useState<Record<string, unknown> | null>(null);
  /** 地址解析进行中 */
  const [geocoding, setGeocoding] = useState(false);
  /** 最近一次已解析的完整地址，避免失焦时对同一地址重复请求 */
  const lastGeocodedRef = useRef('');
  const [loading, setLoading] = useState(isEdit);
  const [notFound, setNotFound] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [recordSheet, setRecordSheet] = useState<RecordSheetPayload | null>(null);
  const [sheetLoading, setSheetLoading] = useState(false);

  /** 所属公司：透传给后续记录的相对人搜索，收窄员工范围 */
  const projectCompanyId = Form.useWatch('companyId', form);

  /** 组织结构下拉：母公司 → 子公司层级 */
  const companyTree = useMemo(() => buildCompanyTree(companies), [companies]);

  // 项目类型取自「系统字典 → 资产管理字典 → 项目属性」，字典项动态维护后表单自动同步
  const projectTypeDict = useDictOptions(PROJECT_TYPE_DICT_CODE);
  const typeValue = Form.useWatch('type', form);
  /** 兼容历史数据：旧项目类型（园区/楼宇…）可能不在字典中，补充回显项避免编辑时丢失 */
  const typeOptions = useMemo(() => {
    const options = projectTypeDict.options;
    if (typeValue && !options.some((option) => option.value === typeValue)) {
      return [
        ...options,
        { value: typeValue, label: PROJECT_TYPE[String(typeValue)] ?? String(typeValue) },
      ];
    }
    return options;
  }, [projectTypeDict.options, typeValue]);

  // 组织结构公司下拉
  useEffect(() => {
    void loadCompanies().then(setCompanies);
  }, []);

  // 省市区级联数据（真实行政区划，按需异步加载并代码分割）
  useEffect(() => {
    let cancelled = false;
    loadRegions()
      .then((list) => {
        if (!cancelled) setRegions(list);
      })
      .catch(() => {
        if (!cancelled) setRegions([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 编辑：回填基本信息 + 分区配置
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    const load = async () => {
      setLoading(true);
      try {
        const [project, zoneList] = await Promise.all([
          api.get<Record<string, unknown>>(`/projects/${id}`),
          api.get<unknown>(`/projects/${id}/zones`),
        ]);
        if (cancelled) return;
        // 图片附件字段单独反解为上传组件值，避免额外 key 污染表单
        const url = typeof project.imageUrl === 'string' ? project.imageUrl : '';
        const fileId = typeof project.imageFileId === 'number' ? project.imageFileId : undefined;
        const projectFields: Record<string, unknown> = { ...project };
        delete projectFields.imageUrl;
        delete projectFields.imageFileId;
        form.setFieldsValue({
          ...projectFields,
          image: url ? { url, fileId } : undefined,
        });
        setProjectBase(project);
        setZones(normalizeList<ProjectZone>(zoneList));
        setNotFound(false);
      } catch (e) {
        if (!cancelled) setNotFound(true);
        message.error(e instanceof Error ? e.message : '加载项目失败');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [id, form]);

  // 省市区回填：等真实行政区划数据就绪后再把 province/city/district 反解为级联路径，
  // 与项目数据加载解耦，避免任意一方先到导致回填丢失或重复请求。
  useEffect(() => {
    if (regions.length === 0 || !projectBase) return;
    form.setFieldValue(
      'region',
      regionPathOf(
        regions,
        projectBase.province as string | undefined,
        projectBase.city as string | undefined,
        projectBase.district as string | undefined,
      ),
    );
  }, [regions, projectBase, form]);

  /**
   * 组装送解析的完整地址：省 + 市 + 区 + 详细地址；
   * 详细地址已包含省级名称时不再重复拼接，避免出现「江苏省江苏省…」。
   */
  const resolveGeocodeAddress = (region?: (string | number)[], detail?: string) => {
    const text = (detail ?? '').trim();
    const province = region?.[0] ? String(region[0]) : '';
    if (province && text.startsWith(province)) return text;
    return `${(region ?? []).map(String).join('')}${text}`;
  };

  /** 执行地址解析并回填；silent=true 时不弹错误提示（自动触发场景） */
  const runGeocode = async (
    region: (string | number)[] | undefined,
    detail: string,
    silent: boolean,
  ) => {
    const address = resolveGeocodeAddress(region, detail);
    setGeocoding(true);
    try {
      const result = await geocodeAddress({ address, city: regionCityHintOf(region) });
      form.setFieldsValue({
        longitude: Number(result.longitude),
        latitude: Number(result.latitude),
      });
      lastGeocodedRef.current = address;
      message.success(
        result.formattedAddress ? `已获取经纬度：${result.formattedAddress}` : '已获取经纬度',
      );
    } catch (e) {
      if (silent) return;
      message.error(e instanceof Error ? e.message : '地址解析失败');
    } finally {
      setGeocoding(false);
    }
  };

  /** 点击「根据地址获取经纬度」：按当前表单值解析并提示失败原因 */
  const handleGeocodeAddress = () => {
    const values = form.getFieldsValue() as {
      region?: (string | number)[];
      address?: string;
    };
    const detail = typeof values.address === 'string' ? values.address.trim() : '';
    if (!detail) {
      message.warning('请先填写详细地址');
      return;
    }
    void runGeocode(values.region, detail, false);
  };

  /** 自动解析守卫：经纬度已填写（人工填写或已解析）时不覆盖 */
  const canAutoGeocode = () => {
    const values = form.getFieldsValue() as {
      address?: string;
      longitude?: number;
      latitude?: number;
    };
    if (values.longitude != null && values.latitude != null) return null;
    const detail = typeof values.address === 'string' ? values.address.trim() : '';
    return detail || null;
  };

  /** 详细地址失焦自动解析：仅在经纬度为空且该地址未解析过时触发 */
  const handleAddressBlur = () => {
    const detail = canAutoGeocode();
    if (!detail) return;
    const region = (form.getFieldsValue() as { region?: (string | number)[] }).region;
    if (lastGeocodedRef.current === resolveGeocodeAddress(region, detail)) return;
    void runGeocode(region, detail, true);
  };

  /** 省市区变更自动解析：利用城市限定提升匹配精度（后选省市区时也能回填） */
  const handleRegionChange = (path?: (string | number)[]) => {
    const detail = canAutoGeocode();
    if (!detail) return;
    void runGeocode(path, detail, true);
  };

  // 后续记录只在编辑态加载：新增态还没有 projectId，记录无处可挂（设计 §6.2）
  useEffect(() => {
    if (!isEdit || !id) {
      setRecordSheet(null);
      return;
    }
    let cancelled = false;
    setSheetLoading(true);
    loadRecordSheet(recordSheetPath('project', Number(id)))
      .then((sheet) => {
        if (cancelled) return;
        setRecordSheet({
          receives: sheet.receives,
          sourceInfo: sheet.sourceInfo,
          disposalRecords: sheet.disposalRecords,
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
   * 校验失败时跳回第一个出错的步骤。
   *
   * <p>本页用 hidden 保持字段挂载，所以**所有**步骤的必填项都参与校验，错误可能落在
   * 当前看不到的步骤上 —— 不跳回去，用户会看到「点了没反应」。用 data-step 反查而不是
   * 维护静态「字段 → 步骤」表：分区配置那一步的字段是运行时增删的，静态表覆盖不全。
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
    if (target !== current) {
      setCurrent(target);
      message.warning('有必填项未完成，已切换到对应步骤');
    }
  };

  const handleNext = async () => {
    try {
      await form.validateFields();
      setCurrent((prev) => Math.min(prev + 1, 2));
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) {
        jumpToErrorStep((e as { errorFields?: { name: (string | number)[] }[] }).errorFields ?? []);
        return;
      }
      // 其余异常由表单自行提示
    }
  };

  const handleAddZone = () => {
    setZones((prev) => [...prev, { name: '', sort: prev.length }]);
  };

  const handleUpdateZone = (index: number, patch: Partial<ProjectZone>) => {
    setZones((prev) => prev.map((zone, i) => (i === index ? { ...zone, ...patch } : zone)));
  };

  const handleRemoveZone = (index: number) => {
    setZones((prev) => prev.filter((_, i) => i !== index));
  };

  const handleSubmit = async () => {
    try {
      const values = (await form.validateFields()) as Record<string, unknown> & {
        region?: (string | number)[];
        image?: ImageValue | null;
      };
      if (zones.some((zone) => !zone.name || !zone.name.trim())) {
        message.error('分区名称不能为空');
        return;
      }
      // 级联路径拆回 project.province / city / district 三个字段；图片值拆回 imageUrl / imageFileId
      const { region, image, ...rest } = values;
      const payload = {
        ...rest,
        ...regionFieldsOf(region),
        imageUrl: image?.url ?? '',
        imageFileId: image?.fileId,
        zones: zones.map((zone, index) => ({
          ...zone,
          name: zone.name.trim(),
          sort: zone.sort ?? index,
        })),
      };
      setSubmitting(true);
      if (isEdit) {
        // 顺序不能反：后续记录依赖主体已存在（设计 §5.4）。
        // 主体保存里含 zones；Task 6 之后「移除有资产或有记录的分区」会返回 400 ——
        // 这类错误必须原样透出，不要改写文案，否则用户看不到是哪个分区、什么原因。
        await api.put(`/projects/${id}`, payload);
        try {
          if (recordSheet) {
            await saveRecordSheet(recordSheetPath('project', Number(id)), recordSheet);
          }
          message.success('保存成功');
        } catch (e) {
          // 「主体已保存、记录失败」的半成品状态必须让使用者知道，否则会以为整单回滚
          message.warning(
            `项目已保存，但后续记录保存失败：${e instanceof Error ? e.message : '未知错误'}，请重试`,
          );
          return;
        }
      } else {
        await api.post('/projects', payload);
        message.success('创建成功');
      }
      navigate('/projects');
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

  const zoneColumns: ColumnsType<ProjectZone> = [
    {
      title: '分区名称',
      key: 'name',
      width: 160,
      render: (_: unknown, _row: ProjectZone, index: number) => (
        <Input
          value={zones[index]?.name}
          placeholder="如 A区"
          onChange={(e) => handleUpdateZone(index, { name: e.target.value })}
        />
      ),
    },
    {
      title: '分区编码',
      key: 'code',
      width: 140,
      render: (_: unknown, _row: ProjectZone, index: number) => (
        <Input
          value={zones[index]?.code}
          placeholder="如 A"
          onChange={(e) => handleUpdateZone(index, { code: e.target.value })}
        />
      ),
    },
    {
      title: '资产面积(㎡)',
      key: 'assetArea',
      width: 170,
      // 只读：由该分区下资产面积合计得出，不在项目模块维护
      render: (_: unknown, zone: ProjectZone) => {
        if (!zone?.id) {
          return <span className="text-gray-400 text-xs">保存后按资产统计</span>;
        }
        const area = Number(zone.assetArea ?? 0);
        return (
          <span title={`由 ${zone.assetCount ?? 0} 项资产面积合计`}>
            {area.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            <span className="text-gray-400 text-xs ml-1">({zone.assetCount ?? 0} 项资产)</span>
          </span>
        );
      },
    },
    {
      title: '排序',
      key: 'sort',
      width: 100,
      render: (_: unknown, _row: ProjectZone, index: number) => (
        <InputNumber
          className="w-full"
          value={zones[index]?.sort}
          onChange={(value) => handleUpdateZone(index, { sort: value ?? undefined })}
        />
      ),
    },
    {
      title: '备注',
      key: 'remark',
      width: 200,
      render: (_: unknown, _row: ProjectZone, index: number) => (
        <Input
          value={zones[index]?.remark}
          onChange={(e) => handleUpdateZone(index, { remark: e.target.value })}
        />
      ),
    },
    {
      title: '操作',
      key: '_actions',
      width: actionsColumnWidth(['详情', '删除']),
      render: (_: unknown, row: ProjectZone, index: number) => (
        <TableActions
          actions={[
            {
              key: 'detail',
              label: '详情',
              icon: <ProfileOutlined />,
              // 新增态的分区还没有 id（要等主体保存后才落库），无处可跳
              disabled: !isEdit || row.id == null,
              onClick: () =>
                navigate(`/projects/${id}/zones/${row.id}`, {
                  state: { from: currentPath(location) },
                }),
            },
            {
              key: 'remove',
              label: '删除',
              icon: <DeleteOutlined />,
              danger: true,
              onClick: () => handleRemoveZone(index),
            },
          ]}
        />
      ),
    },
  ];

  if (isEdit && loading) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-24 text-gray-500">
        <Spin size="large" />
        <span>加载项目信息...</span>
      </div>
    );
  }

  if (isEdit && notFound) {
    return <Empty description="未找到项目" className="py-24" />;
  }

  return (
    <div className="space-y-4 min-w-0">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={goBack} aria-label="返回原页面">
            返回
          </Button>
          <h2 className="text-base font-semibold m-0">{isEdit ? '编辑项目' : '新增项目'}</h2>
        </Space>
      </div>

      <Form form={form} layout="vertical" initialValues={{ status: 1 }}>
        {/* 步骤条：独立卡片，内容区按步骤分组为多个卡片 */}
        <Card className="border border-[var(--ams-border)] mb-4">
          <Steps
            current={current}
            items={[
              { title: '基本信息', description: '名称 / 归属 / 地址定位 / 图片' },
              { title: '项目分区配置', description: '为项目配置分区' },
              { title: '后续记录', description: '处置 / 接收 / 来源' },
            ]}
          />
        </Card>

        {/* 第一步：基本信息（隐藏时保持挂载以保留表单值） */}
        <div data-step={0} hidden={current !== 0}>
          <Card title="基本信息" className="border border-[var(--ams-border)] mb-4">
            <Row gutter={16}>
              <Col xs={24} md={12}>
                <Form.Item
                  name="name"
                  label="项目名称"
                  rules={[{ required: true, message: '请填写项目名称' }]}
                >
                  <Input placeholder="请输入项目名称" />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item
                  name="companyId"
                  label="所属公司"
                  rules={[{ required: true, message: '请选择所属公司' }]}
                >
                  <TreeSelect
                    allowClear
                    showSearch
                    treeDefaultExpandAll
                    treeNodeFilterProp="key"
                    placeholder="请选择所属公司（可输入名称搜索）"
                    treeData={companyTree}
                    listHeight={320}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="type" label="项目类型" extra="取自「系统字典 → 项目属性」">
                  <Select
                    allowClear
                    showSearch
                    optionFilterProp="label"
                    loading={projectTypeDict.loading}
                    placeholder="请选择项目类型"
                    options={typeOptions}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item
                  name="status"
                  label="状态"
                  rules={[{ required: true, message: '请选择状态' }]}
                >
                  <Select placeholder="请选择状态" options={STATUS_OPTIONS} />
                </Form.Item>
              </Col>
            </Row>
          </Card>

          <Card title="地址与定位" className="border border-[var(--ams-border)] mb-4">
            <Row gutter={16}>
              <Col xs={24}>
                <Form.Item
                  name="address"
                  label="详细地址"
                  rules={[{ required: true, message: '请填写详细地址' }]}
                  extra={
                    <div className="flex flex-wrap items-center gap-2 mt-1">
                      <Button
                        size="small"
                        icon={<EnvironmentOutlined />}
                        loading={geocoding}
                        onClick={handleGeocodeAddress}
                      >
                        根据地址获取经纬度
                      </Button>
                      <span className="text-xs text-gray-400">
                        填写详细地址后失焦会自动解析经纬度，也可手动微调
                      </span>
                    </div>
                  }
                >
                  <Input.TextArea
                    rows={2}
                    placeholder="请输入详细地址"
                    onBlur={handleAddressBlur}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="region" label="省市区">
                  <Cascader
                    allowClear
                    options={regions}
                    placeholder="请选择省 / 市 / 区"
                    onChange={handleRegionChange}
                    // 逐级搜索：支持按名称或行政区划代码（如 3208）快速定位
                    showSearch={{
                      filter: (inputValue, path) =>
                        path.some((option) => {
                          const node = option as unknown as RegionOption;
                          return (
                            node.label.includes(inputValue) ||
                            String(node.code ?? '').includes(inputValue)
                          );
                        }),
                    }}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="longitude" label="经度" extra="由详细地址自动解析，可手动微调">
                  <InputNumber className="w-full" placeholder="如 119.0452000" />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="latitude" label="纬度" extra="由详细地址自动解析，可手动微调">
                  <InputNumber className="w-full" placeholder="如 33.5821000" />
                </Form.Item>
              </Col>
            </Row>
          </Card>

          <Card title="项目图片" className="border border-[var(--ams-border)] mb-4">
            <Row gutter={16}>
              <Col xs={24} md={12} lg={8}>
                <Form.Item
                  name="image"
                  label="项目图片"
                  extra="支持 jpg/png，单张不超过 5MB，点击缩略图可预览"
                >
                  <ImageUploadField bizType="project" previewTitle="项目图片" />
                </Form.Item>
              </Col>
            </Row>
          </Card>
        </div>

        {/* 第二步：项目分区配置 */}
        <div data-step={1} hidden={current !== 1}>
          <Card
            title="项目分区配置"
            className="border border-[var(--ams-border)] mb-4"
            extra={
              <Button icon={<PlusOutlined />} onClick={handleAddZone}>
                添加分区
              </Button>
            }
          >
            <div className="text-xs text-gray-500 mb-3">
              为该项目配置分区，资产可归属到具体分区；分区面积由该分区下资产面积自动汇总，无需填写。
            </div>
            <Table
              rowKey={(_row, index) => String(index)}
              columns={zoneColumns}
              dataSource={zones}
              pagination={false}
              size="middle"
              scroll={{ x: 900 }}
              locale={{ emptyText: '暂无分区，点击「添加分区」开始配置' }}
            />
          </Card>
        </div>

        {/* 第三步：后续记录。与资产表单同结构；项目侧处置走可编辑台账（ownerType != asset） */}
        <div data-step={2} hidden={current !== 2}>
          <Card
            title="后续记录"
            className="border border-[var(--ams-border)] mb-4"
            loading={sheetLoading}
          >
            {isEdit ? (
              <RecordSheetSections
                ownerType="project"
                ownerId={Number(id)}
                companyId={projectCompanyId}
                value={recordSheet}
                onChange={setRecordSheet}
              />
            ) : (
              <Alert
                type="info"
                showIcon
                message="保存项目后可录入后续记录"
                description="处置、接收与来源信息都需要先有一个已保存的项目作为归属对象。请先提交本表单建好项目，再回到本步录入。"
              />
            )}
          </Card>
        </div>
      </Form>

      <div className="flex justify-end gap-2">
        {current > 0 && <Button onClick={() => setCurrent(current - 1)}>上一步</Button>}
        <Button onClick={goBack}>取消</Button>
        {current < 2 ? (
          <Button type="primary" onClick={() => void handleNext()}>
            下一步
          </Button>
        ) : (
          // 提交按钮按模式判定：本页承载新增与编辑两条路径（/projects/create、/projects/:id/edit），
          // 且两个路由都是钻取路由（不在 PATH_TO_CODE 镜像里），故按 isEdit 显式取码。
          // 无权时隐藏提交、保留「取消」，避免用户填完一整页才在最后一步吃 403。
          <PermissionGuard perm={isEdit ? 'asset.project:update' : 'asset.project:create'}>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={submitting}
              onClick={() => void handleSubmit()}
            >
              {isEdit ? '保存' : '提交'}
            </Button>
          </PermissionGuard>
        )}
      </div>
    </div>
  );
}
