import { useState } from 'react';

/**
 * 封面占位图（数据 URI）。
 *
 * <p>项目/资产的 imageUrl 可能为空（历史数据、未上传），也可能指向已失效的对象存储地址；
 * 两种情况都回落到这张内置占位图，避免渲染出破图或空白块。
 * 用内联 SVG 生成而非静态资源，是为了不新增打包体积、也不依赖后端文件服务可用。
 */
const PLACEHOLDER_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 260" width="400" height="260">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#f8fafc"/>
      <stop offset="1" stop-color="#e8eef5"/>
    </linearGradient>
  </defs>
  <rect width="400" height="260" fill="url(#bg)"/>
  <g fill="#cbd5e1">
    <rect x="52" y="118" width="62" height="102" rx="3"/>
    <rect x="122" y="74" width="76" height="146" rx="3"/>
    <rect x="206" y="98" width="58" height="122" rx="3"/>
    <rect x="272" y="132" width="76" height="88" rx="3"/>
  </g>
  <g fill="#eef2f7">
    <rect x="132" y="88" width="12" height="12" rx="1"/>
    <rect x="152" y="88" width="12" height="12" rx="1"/>
    <rect x="172" y="88" width="12" height="12" rx="1"/>
    <rect x="132" y="110" width="12" height="12" rx="1"/>
    <rect x="152" y="110" width="12" height="12" rx="1"/>
    <rect x="172" y="110" width="12" height="12" rx="1"/>
    <rect x="132" y="132" width="12" height="12" rx="1"/>
    <rect x="152" y="132" width="12" height="12" rx="1"/>
    <rect x="172" y="132" width="12" height="12" rx="1"/>
    <rect x="216" y="112" width="10" height="10" rx="1"/>
    <rect x="234" y="112" width="10" height="10" rx="1"/>
    <rect x="216" y="132" width="10" height="10" rx="1"/>
    <rect x="234" y="132" width="10" height="10" rx="1"/>
    <rect x="62" y="132" width="10" height="10" rx="1"/>
    <rect x="82" y="132" width="10" height="10" rx="1"/>
    <rect x="62" y="154" width="10" height="10" rx="1"/>
    <rect x="82" y="154" width="10" height="10" rx="1"/>
    <rect x="284" y="146" width="12" height="12" rx="1"/>
    <rect x="306" y="146" width="12" height="12" rx="1"/>
    <rect x="328" y="146" width="12" height="12" rx="1"/>
    <rect x="284" y="170" width="12" height="12" rx="1"/>
    <rect x="306" y="170" width="12" height="12" rx="1"/>
    <rect x="328" y="170" width="12" height="12" rx="1"/>
  </g>
  <g stroke="#cbd5e1" stroke-width="2" fill="none" stroke-linecap="round">
    <path d="M40 220h320"/>
  </g>
  <g transform="translate(200 196)">
    <circle cx="0" cy="0" r="0" fill="none"/>
  </g>
  <text x="200" y="243" text-anchor="middle" font-family="PingFang SC, Microsoft YaHei, Helvetica, Arial, sans-serif" font-size="15" fill="#94a3b8">暂无图片</text>
</svg>`;

export const COVER_PLACEHOLDER = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(PLACEHOLDER_SVG)}`;

/** 空值判断：null / undefined / 空串 / 纯空格都算无图 */
export const hasCover = (src?: string | null): boolean => !!src && src.trim().length > 0;

export interface CoverImageProps {
  /** 图片地址；为空或加载失败时展示占位图 */
  src?: string | null;
  alt?: string;
  /** 容器类名（控制尺寸、圆角） */
  className?: string;
  /** img 自身类名（控制 object-fit，默认 cover） */
  imgClassName?: string;
}

/**
 * 统一封面的图片组件：自带占位图回落。
 *
 * <p>加载失败（对象存储不可用、图片被清理）同样切换到占位图，
 * 覆盖「有 URL 但取不到图」的情况，而不只是「没有 URL」。
 */
export const CoverImage = ({ src, alt = '', className, imgClassName }: CoverImageProps) => {
  const [failed, setFailed] = useState(false);
  const resolved = !hasCover(src) || failed ? COVER_PLACEHOLDER : (src as string);
  const isPlaceholder = resolved === COVER_PLACEHOLDER;

  return (
    <div className={className ?? 'w-full h-full'} style={{ overflow: 'hidden' }}>
      <img
        src={resolved}
        alt={isPlaceholder ? `${alt}（暂无图片）` : alt}
        loading="lazy"
        onError={() => setFailed(true)}
        className={imgClassName ?? 'w-full h-full object-cover'}
      />
    </div>
  );
};
