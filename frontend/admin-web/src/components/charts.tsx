/**
 * 轻量内联 SVG 图表组件。
 *
 * <p>刻意不引入 echarts/recharts：项目详情的图表只用于「分布 + 趋势」两类表达，
 * 自绘可避免数百 KB 的依赖，也与现有后台的极简视觉一致。
 * 所有图表都做空数据兜底，不渲染出残缺图形。
 */

/** 图例 / 环形图配色：按序号循环取用，保证同一分项在各处颜色一致 */
export const CHART_COLORS = [
  '#1677ff',
  '#52c41a',
  '#faad14',
  '#f5222d',
  '#722ed1',
  '#13c2c2',
  '#eb2f96',
  '#fa8c16',
  '#2f54eb',
  '#a0d911',
];

/** 按索引取色，超出长度后循环 */
export const chartColor = (index: number) => CHART_COLORS[index % CHART_COLORS.length];

export interface SliceDatum {
  label: string;
  value: number;
  color: string;
}

export interface DonutChartProps {
  slices: SliceDatum[];
  /** 图形边长(px) */
  size?: number;
  /** 环宽(px) */
  thickness?: number;
  /** 圆心主文案（如占比） */
  centerValue?: string;
  /** 圆心副文案（如合计） */
  centerLabel?: string;
}

/**
 * 环形图：用 stroke-dasharray 逐段绘制，避免依赖 canvas。
 * 从 12 点方向顺时针起笔（progress-rotate -90）。
 */
export const DonutChart = ({
  slices,
  size = 132,
  thickness = 16,
  centerValue,
  centerLabel,
}: DonutChartProps) => {
  const total = slices.reduce((sum, s) => sum + (Number.isFinite(s.value) ? s.value : 0), 0);
  const radius = (size - thickness) / 2;
  const circumference = 2 * Math.PI * radius;
  const center = size / 2;

  if (total <= 0) {
    return (
      <svg width={size} height={size} role="img" aria-label="暂无数据">
        <circle
          cx={center}
          cy={center}
          r={radius}
          fill="none"
          stroke="#f0f0f0"
          strokeWidth={thickness}
        />
        <text x={center} y={center + 4} textAnchor="middle" fontSize={12} fill="#bfbfbf">
          暂无数据
        </text>
      </svg>
    );
  }

  let accumulated = 0;

  return (
    <svg width={size} height={size} role="img" aria-label="占比分布">
      <g transform={`rotate(-90 ${center} ${center})`}>
        {slices.map((slice) => {
          const ratio = slice.value / total;
          const length = ratio * circumference;
          const dash = `${length} ${circumference - length}`;
          const offset = -accumulated * circumference;
          accumulated += ratio;
          return (
            <circle
              key={slice.label}
              cx={center}
              cy={center}
              r={radius}
              fill="none"
              stroke={slice.color}
              strokeWidth={thickness}
              strokeDasharray={dash}
              strokeDashoffset={offset}
            >
              <title>{`${slice.label} ${slice.value}（${(ratio * 100).toFixed(1)}%）`}</title>
            </circle>
          );
        })}
      </g>
      {centerValue && (
        <text
          x={center}
          y={centerLabel ? center - 2 : center + 5}
          textAnchor="middle"
          fontSize={17}
          fontWeight={600}
          fill="#1f2937"
        >
          {centerValue}
        </text>
      )}
      {centerLabel && (
        <text x={center} y={center + 16} textAnchor="middle" fontSize={11} fill="#8c8c8c">
          {centerLabel}
        </text>
      )}
    </svg>
  );
};

export interface BarChartDatum {
  label: string;
  value: number;
}

export interface MiniBarChartProps {
  data: BarChartDatum[];
  height?: number;
  /** 柱子颜色 */
  color?: string;
  /** 数值格式化（提示气泡用） */
  formatValue?: (value: number) => string;
}

/**
 * 柱状图：纯 CSS 高度百分比实现。
 * 数值为 0 时仍保留 2px 基线，让「有月份没收入」这件事可见，而不是被当成缺数据。
 */
export const MiniBarChart = ({
  data,
  height = 132,
  color = '#1677ff',
  formatValue = (v) => String(v),
}: MiniBarChartProps) => {
  const max = data.reduce((peak, d) => Math.max(peak, d.value), 0);

  if (data.length === 0) {
    return (
      <div className="flex items-center justify-center text-xs text-gray-400" style={{ height }}>
        暂无数据
      </div>
    );
  }

  return (
    <div className="w-full">
      <div className="flex items-end gap-1" style={{ height }}>
        {data.map((d) => {
          const percent = max <= 0 ? 0 : (d.value / max) * 100;
          return (
            <div
              key={d.label}
              className="flex-1 flex items-end h-full group relative"
              title={`${d.label} ${formatValue(d.value)}`}
            >
              <div
                className="w-full rounded-t transition-all group-hover:opacity-80"
                style={{
                  height: d.value > 0 ? `${Math.max(percent, 3)}%` : '2px',
                  backgroundColor: d.value > 0 ? color : '#e5e7eb',
                }}
              />
            </div>
          );
        })}
      </div>
      <div className="flex gap-1 mt-1">
        {data.map((d) => (
          <div key={d.label} className="flex-1 text-center text-[10px] text-gray-400 truncate">
            {d.label}
          </div>
        ))}
      </div>
    </div>
  );
};

export interface RateBarProps {
  /** 百分比 0-100 */
  percent: number;
  color?: string;
  /** 右侧数值文案，默认取 percent */
  valueText?: string;
}

/** 水平占比条：用于「资产出租率 / 上月收缴率」这类单值进度表达 */
export const RateBar = ({ percent, color = '#1677ff', valueText }: RateBarProps) => {
  const safe = Math.max(0, Math.min(100, Number.isFinite(percent) ? percent : 0));
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-1.5 rounded-full bg-gray-100 overflow-hidden">
        <div
          className="h-full rounded-full transition-all"
          style={{ width: `${safe}%`, backgroundColor: color }}
        />
      </div>
      <span className="text-xs text-gray-500 tabular-nums w-14 text-right">
        {valueText ?? `${safe.toFixed(2)}%`}
      </span>
    </div>
  );
};

export interface LegendItem {
  label: string;
  value: string | number;
  color: string;
}

/** 图例列表：环形图/柱状图旁边的分项说明 */
export const ChartLegend = ({ items }: { items: LegendItem[] }) => (
  <ul className="flex-1 min-w-0 space-y-1.5">
    {items.map((item) => (
      <li key={item.label} className="flex items-center gap-2 text-xs">
        <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: item.color }} />
        <span className="text-gray-600 truncate flex-1">{item.label}</span>
        <span className="text-gray-800 tabular-nums">{item.value}</span>
      </li>
    ))}
  </ul>
);
