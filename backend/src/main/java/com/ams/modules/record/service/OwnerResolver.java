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
import org.springframework.stereotype.Service;

/**
 * 记录宿主解析（设计 §5.3）：把 {@code (ownerType, ownerId)} 变成「可访问的归属公司」。
 *
 * <p>每个 record-sheet 端点都必须先过这里，理由有三：
 * <ol>
 *   <li><b>存在性</b>：宿主不存在时返回 404，而不是安静地写出一条悬空记录
 *       （设计 §7.2 明确禁止）；</li>
 *   <li><b>对象级数据范围</b>：记录表本身没有公司列，只能沿宿主回溯，
 *       再用 {@link RbacService#assertCompanyAccess} 断言（含全局公司切换与角色排除清单）；</li>
 *   <li><b>一致性</b>：三种主体的归属推导口径集中在一处，避免「资产按 A 口径、分区按 B 口径」。</li>
 * </ol>
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
     * 断言宿主存在且当前账号有数据范围，返回其归属公司（可能为 null）。
     *
     * @throws AppException 宿主不存在 → {@code NOT_FOUND}；越权 → {@code 403}
     */
    public Long assertAccessible(RecordOwnerType type, Long ownerId) {
        if (ownerId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "缺少记录归属对象编号");
        }
        Long companyId = switch (type) {
            case ASSET -> {
                Asset asset = assetMapper.selectById(ownerId);
                require(asset != null, type, ownerId);
                yield ownershipResolver.ofAsset(ownerId);
            }
            case PROJECT -> {
                Project project = projectMapper.selectById(ownerId);
                require(project != null, type, ownerId);
                yield ownershipResolver.ofProject(ownerId);
            }
            case ZONE -> {
                ProjectZone zone = projectZoneMapper.selectActiveById(ownerId);
                require(zone != null, type, ownerId);
                yield ownershipResolver.ofProject(zone.getProjectId());
            }
        };
        rbacService.assertCompanyAccess(SecurityUtils.current(), companyId);
        return companyId;
    }

    private void require(boolean exists, RecordOwnerType type, Long ownerId) {
        if (!exists) {
            throw new AppException(
                    ErrorCode.NOT_FOUND, "记录归属对象不存在：" + type.code() + "#" + ownerId);
        }
    }
}
