import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
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
  TreeSelect,
  message,
} from 'antd';
import type { FormInstance } from 'antd/es/form';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { api } from '@/lib/api';
import { ImageUploadField, type ImageValue } from '@/components/ImageUploadField';
import { useDictOptions, useRemoteOptions, type SelectOption } from '@/lib/dict';
import * as L from '@/lib/labels';
import { buildCompanyTree, loadCompanies, type CompanyOption } from '@/lib/org';
import { useBackNavigate } from '@/lib/navigation';

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

export function AssetFormPage() {
  const navigate = useNavigate();
  const { id } = useParams();
  const isEdit = Boolean(id);
  const goBack = useBackNavigate('/assets');

  const [form] = Form.useForm();
  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [loading, setLoading] = useState(isEdit);
  const [notFound, setNotFound] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const companyTree = useMemo(() => buildCompanyTree(companies), [companies]);

  // ---- 四级联动：资产公司 → 项目 → 分区；资产公司 → 责任部门 → 责任人 ----
  const assetCompanyId = Form.useWatch('assetCompanyId', form);
  const watchProjectId = Form.useWatch('projectId', form);
  const watchDepartmentId = Form.useWatch('responsibleDepartmentId', form);

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
  useCascadeReset(form, 'responsibleDepartmentId', ['responsibleUserId']);

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
        await api.put(`/assets/${id}`, payload);
        message.success('保存成功');
      } else {
        await api.post('/assets', payload);
        message.success('创建成功');
      }
      navigate('/assets');
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) return;
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

      <Form form={form} layout="vertical" initialValues={{ assetType: 'property' }}>
        {/* 乐观锁版本号：编辑保存时随 PUT 回传，缺失会被判定为并发冲突 */}
        <Form.Item name="version" hidden>
          <InputNumber />
        </Form.Item>

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
                extra="项目、责任部门均按资产公司级联"
              >
                <TreeSelect
                  allowClear
                  showSearch
                  treeDefaultExpandAll
                  treeNodeFilterProp="key"
                  placeholder="请选择资产公司（可输入名称搜索）"
                  treeData={companyTree}
                  listHeight={320}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12} lg={8}>
              <Form.Item
                name="projectId"
                label="项目"
                rules={[{ required: true, message: '请选择项目' }]}
              >
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  loading={projectOptions.loading}
                  disabled={!assetCompanyId}
                  placeholder={assetCompanyId ? '请选择项目（可搜索）' : '请先选择资产公司'}
                  options={projectOptions.options}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12} lg={8}>
              <Form.Item name="zoneId" label="分区">
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  loading={zoneOptions.loading}
                  disabled={!watchProjectId}
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
              <Form.Item name="floorNo" label="分区楼层">
                <InputNumber className="w-full" placeholder="请输入楼层数字" precision={0} />
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
            选项取自「系统管理 → 系统字典 → 资产管理字典」，字典项不足时可前往补充，表单会自动同步。
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
      </Form>

      <div className="flex justify-end gap-2">
        <Button onClick={goBack}>取消</Button>
        <Button
          type="primary"
          icon={<SaveOutlined />}
          loading={submitting}
          onClick={() => void handleSubmit()}
        >
          {isEdit ? '保存' : '提交'}
        </Button>
      </div>
    </div>
  );
}
