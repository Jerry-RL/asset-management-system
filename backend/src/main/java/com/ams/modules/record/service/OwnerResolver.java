package com.ams.modules.record.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.record.RecordOwnerType;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 记录宿主解析（设计 §5.3）：把 {@code (ownerType, ownerId)} 变成「可访问的归属公司」。
 *
 * <p>每个 record-sheet 端点都必须先过这里，理由有四：
 * <ol>
 *   <li><b>存在性</b>：宿主不存在时返回 404，而不是安静地写出一条悬空记录
 *       （设计 §7.2 明确禁止）；</li>
 *   <li><b>对象级数据范围</b>：记录表本身没有公司列，只能沿宿主回溯，
 *       再断言当前账号是否有权访问该归属公司（含全局公司切换与角色排除清单）；</li>
 *   <li><b>一致性</b>：三种主体的归属推导口径集中在一处，避免「资产按 A 口径、分区按 B 口径」；</li>
 *   <li><b>不泄露存在性</b>（设计 §5.3 第 5 条）：对象级越权与宿主不存在返回<b>完全相同</b>的
 *       {@link ErrorCode#NOT_FOUND} 与文案 —— 否则用 403 / 404 之差就能把宿主 id 当探针探测。
 *       注意这与「无功能权限（{@code @RequiresPerm}）→ 403」是两回事，那一层不归本类管。</li>
 * </ol>
 *
 * <p><b>三种主体一律「未软删才算存在」</b>：已软删的宿主不解析归属、也不允许再挂记录。
 *
 * <p>返回的公司在归属推导不出时为 {@code null}：那是既有约定 ——
 * 受限账号被拒、不受限账号放行，不需要在这里另写一套判断（见
 * {@link OwnershipResolver} 的类注释）。
 */
@Service
public class OwnerResolver {

    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;
    private final ProjectZoneMapper projectZoneMapper;
    private final OwnershipResolver ownershipResolver;
    private final RbacService rbacService;

    public OwnerResolver(
            AssetMapper assetMapper,
            ProjectMapper projectMapper,
            ProjectZoneMapper projectZoneMapper,
            OwnershipResolver ownershipResolver,
            RbacService rbacService) {
        this.assetMapper = assetMapper;
        this.projectMapper = projectMapper;
        this.projectZoneMapper = projectZoneMapper;
        this.ownershipResolver = ownershipResolver;
        this.rbacService = rbacService;
    }

    /**
     * 断言宿主存在（且未软删）并且当前账号可访问其归属公司，返回该归属公司（可能为 null）。
     *
     * <p>「宿主不存在」与「对象级越权」一律抛 {@link ErrorCode#NOT_FOUND} 且文案相同，
     * 调用方无法据响应区分二者（设计 §5.3 第 5 条）。
     *
     * @throws AppException ownerId 缺失 → {@code BAD_REQUEST}；
     *     宿主不存在<b>或对象级越权</b> → {@code NOT_FOUND}（两者同状态码、同文案）
     */
    public Long assertAccessible(RecordOwnerType type, Long ownerId) {
        if (ownerId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "缺少记录归属对象编号");
        }
        Long companyId = switch (type) {
            case ASSET -> {
                // 本仓库逻辑删字段名（deleted_at）与 MP 配置的 deleted 不一致，自动过滤不生效，
                // 故显式过滤。Asset/Project 没有 deletedAt 属性，而 LambdaQueryWrapper 的列参数
                // 类型是 SFunction 而非 String，所以只能用 apply("deleted_at IS NULL") 这一
                // 字符串逃生口（字面量常量，无入参，不构成注入面）；分区则复用
                // ProjectZoneMapper.selectActiveById（Task 6 也要用），两处写法不同是有意为之。
                Asset asset = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getId, ownerId)
                        .apply("deleted_at IS NULL"));
                require(asset != null, type, ownerId);
                yield ownershipResolver.ofAsset(ownerId);
            }
            case PROJECT -> {
                Project project = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                        .eq(Project::getId, ownerId)
                        .apply("deleted_at IS NULL"));
                require(project != null, type, ownerId);
                yield ownershipResolver.ofProject(ownerId);
            }
            case ZONE -> {
                ProjectZone zone = projectZoneMapper.selectActiveById(ownerId);
                require(zone != null, type, ownerId);
                yield ownershipResolver.ofProject(zone.getProjectId());
            }
        };
        // 越权刻意收敛成与「不存在」完全一致的 404（设计 §5.3 第 5 条 / 验收 8）：
        // 用 403 区分「存在但无权」会把宿主 id 变成存在性探针。不要「顺手修回」403。
        if (!rbacService.canAccessCompany(SecurityUtils.current(), companyId)) {
            throw new AppException(ErrorCode.NOT_FOUND, notFoundMessage(type, ownerId));
        }
        return companyId;
    }

    private void require(boolean exists, RecordOwnerType type, Long ownerId) {
        if (!exists) {
            throw new AppException(ErrorCode.NOT_FOUND, notFoundMessage(type, ownerId));
        }
    }

    /** 宿主不存在与对象级越权共用同一条文案，保证两条路径不会各自漂移。 */
    private static String notFoundMessage(RecordOwnerType type, Long ownerId) {
        return "记录归属对象不存在：" + type.code() + "#" + ownerId;
    }
}
