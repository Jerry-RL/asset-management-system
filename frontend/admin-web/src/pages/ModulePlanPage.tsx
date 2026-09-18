import { Alert, Card, Empty, Space, Tag, Typography } from 'antd';
import { Link, useLocation } from 'react-router-dom';
import { ArrowRightOutlined, CheckCircleOutlined, ToolOutlined } from '@ant-design/icons';
import { MODULE_PLAN_ORDER, modulePlanByPath, type ModulePlan } from '@/lib/modulePlan';
import { getPathIcon } from '@/lib/menuIcons';
import { routeTitle } from '@/lib/routeRegistry';

const { Paragraph, Text } = Typography;

/**
 * 「资产经营管理」子模块的规划页（V61）。
 *
 * <p><b>这一页为什么存在</b>：本次交付的是**菜单骨架**，7 个模块的功能尚未实现。
 * 侧边栏只渲染已注册路由，所以每个菜单项都必须有页面 —— 直接指向既有页面又会违反
 * 「同一 path 只落一行」的约定（会出现两个指向同一页面的入口）。因此每个子菜单有
 * 自己的页面，页内说清三件事：
 *
 * <ol>
 *   <li><b>还没做</b>：明确告知功能未实现，避免使用者以为系统坏了；</li>
 *   <li><b>要做什么</b>：列出规划中的能力，作为后续迭代的需求锚点；</li>
 *   <li><b>现在能用什么</b>：给出既有页面的直接跳转 —— 今天就能干活，不必等新功能。</li>
 * </ol>
 *
 * <p>配置见 {@link MODULE_PLANS}；本组件按当前路由取配置，因此 7 条路由共用同一个组件
 * （改一处文案不必改七份）。
 */
export function ModulePlanPage() {
  const { pathname } = useLocation();
  const plan: ModulePlan | undefined = modulePlanByPath(pathname);

  // 未配置的路由：不白屏，给出可读的兜底（新增子菜单时忘了配 modulePlan 会走到这里）
  if (!plan) {
    return (
      <div className="p-4">
        <Card>
          <Empty
            description={
              <span>
                该模块尚未登记规划说明（{pathname}）。
                <br />
                请在 <Text code>lib/modulePlan.ts</Text> 的 <Text code>MODULE_PLANS</Text>{' '}
                中补充配置。
              </span>
            }
          />
        </Card>
      </div>
    );
  }

  const order = MODULE_PLAN_ORDER.indexOf(plan.path);
  const next = order >= 0 ? MODULE_PLAN_ORDER[order + 1] : undefined;

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-3">
          <span className="text-2xl leading-none">{getPathIcon(plan.path)}</span>
          <div>
            <h1 className="text-xl font-semibold m-0">{plan.title}</h1>
            <p className="text-gray-500 text-sm m-0 mt-1">{plan.purpose}</p>
          </div>
        </div>
        <Space>
          <Tag icon={<ToolOutlined />} color="warning" className="m-0">
            功能规划中
          </Tag>
          {next && (
            <Link to={next}>
              {routeTitle(next)} <ArrowRightOutlined />
            </Link>
          )}
        </Space>
      </div>

      <Alert
        type="info"
        showIcon
        message="本模块尚未实现"
        description={
          <span>
            当前交付的是菜单骨架与规划说明：下方「规划能力」还没有对应的功能页面与接口，
            点进来不会看到数据。若需立刻办理相关业务，请使用下方「现在可用的入口」中的既有页面。
            如需把本模块排进迭代，请以「规划能力」为需求清单。
          </span>
        }
      />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <Card size="small" title="规划能力（尚未实现）">
          <ul className="m-0 pl-5 space-y-2 text-sm text-gray-700">
            {plan.capabilities.map((capability) => (
              <li key={capability}>{capability}</li>
            ))}
          </ul>
        </Card>

        <Card
          size="small"
          title="现在可用的入口"
          extra={
            plan.links.length > 0 ? (
              <Text type="secondary" className="text-xs">
                既有模块已覆盖部分能力
              </Text>
            ) : null
          }
        >
          {plan.links.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="本模块尚无可用能力，全部为规划内容"
            />
          ) : (
            <div className="space-y-2">
              {plan.links.map((link) => (
                <Link
                  key={link.to}
                  to={link.to}
                  className="flex items-start gap-2 rounded-md border border-gray-200 p-3 hover:border-blue-400 hover:bg-blue-50/40"
                >
                  <CheckCircleOutlined className="text-green-600 mt-0.5" />
                  <span className="min-w-0">
                    <span className="block text-sm font-medium text-gray-900">{link.label}</span>
                    <span className="block text-xs text-gray-500 mt-0.5">{link.desc}</span>
                  </span>
                </Link>
              ))}
            </div>
          )}
        </Card>
      </div>

      <Card size="small" title="说明">
        <Paragraph className="text-sm text-gray-600 mb-0">
          「资产经营管理」是把招租、签约、风险、资源、其他使用、巡检、维修这七件事按**经营管理**
          的视角串成一组。它们的底层数据与既有模块共用（例如招租仍落在招租单据、签约仍落在合同台账），
          本页只是新的编排入口，不会另起一套台账 ——
          因此在功能落地前，使用既有页面办理业务不会产生数据分叉。
        </Paragraph>
      </Card>
    </div>
  );
}
