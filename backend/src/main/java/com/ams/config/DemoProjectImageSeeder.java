package com.ams.config;

import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.system.entity.FileMetadata;
import com.ams.modules.system.service.FileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 演示用项目封面图补齐（仅在 demo profile 下生效）。
 *
 * <p>背景：项目管理新增了卡片视图，主图取 {@code project.image_url}；而演示项目此前
 * 全部没有图片，卡片会整屏「暂无图片」，无法体现该视图。此处为每个缺图项目生成一张
 * 确定性的 SVG 封面并落到对象存储，写入 {@code file_metadata} 后回填项目图片地址。
 *
 * <p>选择 SVG 而非位图：文本即可生成、体积极小、无需引入绘图库或外部图片依赖，
 * 浏览器在 {@code <img>} 中可直接渲染。
 *
 * <p>幂等：仅处理 {@code image_url} 为空的项目；已有图片（含用户后台上传的真实照片）不动。
 */
@Component
@Profile("demo")
@Order(210)
public class DemoProjectImageSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoProjectImageSeeder.class);

    /** 每个项目属性对应一组配色（主色 → 辅色），让同属性项目的封面观感统一 */
    private static final String[] PROPERTY_PALETTE = {"#1d4ed8", "#3b82f6"};
    private static final String[] LAND_PALETTE = {"#0f766e", "#14b8a6"};
    private static final String[] DEFAULT_PALETTE = {"#475569", "#94a3b8"};

    private static final int WIDTH = 800;
    private static final int HEIGHT = 500;

    private final ProjectMapper projectMapper;
    private final FileService fileService;

    public DemoProjectImageSeeder(ProjectMapper projectMapper, FileService fileService) {
        this.projectMapper = projectMapper;
        this.fileService = fileService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 刻意不加 @Transactional：每个项目的封面独立提交，
        // 单张失败只影响该项目，不会因一个异常把整批（乃至启动）拖垮
        List<Project> missing = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .and(w -> w.isNull(Project::getImageUrl).or().eq(Project::getImageUrl, "")));
        if (missing.isEmpty()) {
            log.info("[demo-project-image] 项目封面均已存在，跳过");
            return;
        }
        int created = 0;
        for (Project project : missing) {
            try {
                String svg = renderCover(project);
                FileMetadata meta = fileService.storeBytes(
                        svg.getBytes(StandardCharsets.UTF_8),
                        "project-" + project.getId() + "-cover.svg",
                        "image/svg+xml",
                        "project");
                project.setImageUrl(objectUrl(meta));
                project.setImageFileId(meta.getId());
                projectMapper.updateById(project);
                created++;
            } catch (Exception e) {
                // 单张封面失败不应影响启动，也不应连累其余项目
                log.warn("[demo-project-image] 生成项目封面失败 id={} err={}",
                        project.getId(), e.getMessage());
            }
        }
        log.info("[demo-project-image] 项目封面补齐完成：新增 {} / 待补 {} 张", created, missing.size());
    }

    /**
     * 生成项目封面 SVG：双色渐变底 + 几何装饰 + 项目名与地址。
     *
     * <p>非模板引擎，纯字符串拼接（内容来自库内项目字段）。文本经 XML 转义，
     * 避免项目名含 {@code < & "} 时破坏 SVG 结构。
     */
    private String renderCover(Project project) {
        String[] palette = palette(project.getType());
        String title = escape(project.getName() == null ? "项目" : project.getName());
        String location = escape(location(project));
        String badge = escape(typeLabel(project.getType()));

        return """
                <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">
                  <defs>
                    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
                      <stop offset="0%%" stop-color="%s"/>
                      <stop offset="100%%" stop-color="%s"/>
                    </linearGradient>
                  </defs>
                  <rect width="100%%" height="100%%" fill="url(#bg)"/>
                  <g fill="#ffffff" fill-opacity="0.10">
                    <circle cx="660" cy="90" r="150"/>
                    <circle cx="120" cy="430" r="110"/>
                    <rect x="520" y="300" width="260" height="180" rx="24" transform="rotate(-18 650 390)"/>
                  </g>
                  <g fill="#ffffff" fill-opacity="0.28">
                    <rect x="56" y="52" width="54" height="6" rx="3"/>
                  </g>
                  <text x="56" y="70" fill="#ffffff" fill-opacity="0.9"
                        font-family="'PingFang SC','Microsoft YaHei',sans-serif" font-size="20">%s</text>
                  <text x="56" y="252" fill="#ffffff"
                        font-family="'PingFang SC','Microsoft YaHei',sans-serif"
                        font-size="44" font-weight="600">%s</text>
                  <text x="56" y="300" fill="#ffffff" fill-opacity="0.82"
                        font-family="'PingFang SC','Microsoft YaHei',sans-serif" font-size="20">%s</text>
                </svg>
                """
                .formatted(WIDTH, HEIGHT, WIDTH, HEIGHT,
                        palette[0], palette[1], badge, title, location);
    }

    private static String[] palette(String type) {
        return switch (type == null ? "" : type) {
            case "property" -> PROPERTY_PALETTE;
            case "land" -> LAND_PALETTE;
            default -> DEFAULT_PALETTE;
        };
    }

    private static String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "property" -> "房产类项目";
            case "land" -> "土地类项目";
            case "park" -> "园区项目";
            case "building" -> "楼宇项目";
            default -> "资产项目";
        };
    }

    /** 封面副标题：省市区 + 详细地址，与卡片上的地址栏口径一致 */
    private static String location(Project project) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {project.getProvince(), project.getCity(),
                project.getDistrict(), project.getAddress()}) {
            if (part != null && !part.isBlank()) {
                sb.append(part);
            }
        }
        return sb.isEmpty() ? "地址待完善" : sb.toString();
    }

    private static String objectUrl(FileMetadata meta) {
        // 与 LocalObjectStorageClient#resolveUrl 同构；此处直接落库为可内嵌的公开地址
        return "/api/v1/files/object/" + meta.getObjectKey();
    }

    private static String escape(String raw) {
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
