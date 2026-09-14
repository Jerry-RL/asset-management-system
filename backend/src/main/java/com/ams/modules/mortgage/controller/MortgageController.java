package com.ams.modules.mortgage.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.mortgage.dto.MortgageRecordInput;
import com.ams.modules.mortgage.dto.MortgageRecordView;
import com.ams.modules.mortgage.dto.MortgageTargetOption;
import com.ams.modules.mortgage.service.MortgageRecordService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.RequiresPerm;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 抵押记录接口（V56）。
 *
 * <p>权限码固定 4 个：{@code deed.mortgage:view|create|update|delete}。
 * 「生效」用 {@code :update} 而不是 {@code :approve}：本期没有审批环节，而仓内
 * {@code :approve} 特指「审批别人的单」（处置 / 占用 / 自用）；推进状态机一律用
 * {@code :update}（{@code operation.disposal} 的 execute / complete 即是）。
 *
 * <p><b>为什么解押不在这里</b>：解押是既有能力
 * （{@code POST /api/v1/mortgages/{id}/release/submit}，走 {@code mortgage_release}
 * 审批流），V56 没有改动它的语义。把它搬过来只会让「同一个资源两个控制器」，
 * 而这个模块要解决的问题是标的层级，不是解押。
 *
 * <p><b>{@code /target-options} 必须声明在 {@code /{id}} 之前</b>：否则
 * 「target-options」会被 {@code @PathVariable} 抢匹配，然后因无法转成 {@code Long} 直接 500。
 * （Spring 的路径匹配对**同一**控制器内的字面段优先，但依赖这个顺序比依赖匹配器更好读。）
 */
@RestController
@RequestMapping("/api/v1/mortgages")
public class MortgageController {

    private final MortgageRecordService service;

    public MortgageController(MortgageRecordService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPerm("deed.mortgage:view")
    public ApiResponse<PageResult<MortgageRecordView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(
                service.page(page, pageSize, status, targetType, companyId, keyword),
                TraceIdUtil.get());
    }

    /** 标的选题项（按项目类型 + 所属公司联动过滤）。 */
    @GetMapping("/target-options")
    @RequiresPerm("deed.mortgage:view")
    public ApiResponse<PageResult<MortgageTargetOption>> targetOptions(
            @RequestParam String targetType,
            @RequestParam Long companyId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long pageSize) {
        return ApiResponse.ok(
                service.targetOptions(targetType, companyId, keyword, page, pageSize),
                TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    @RequiresPerm("deed.mortgage:view")
    public ApiResponse<MortgageRecordView> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id), TraceIdUtil.get());
    }

    @PostMapping
    @RequiresPerm("deed.mortgage:create")
    @Audited(module = "mortgage", action = "create")
    public ApiResponse<MortgageRecordView> create(@RequestBody MortgageRecordInput input) {
        return ApiResponse.ok(service.create(input), TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    @RequiresPerm("deed.mortgage:update")
    @Audited(module = "mortgage", action = "update")
    public ApiResponse<MortgageRecordView> update(
            @PathVariable Long id, @RequestBody MortgageRecordInput input) {
        return ApiResponse.ok(service.update(id, input), TraceIdUtil.get());
    }

    @DeleteMapping("/{id}")
    @RequiresPerm("deed.mortgage:delete")
    @Audited(module = "mortgage", action = "delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    /**
     * 生效：草稿 → 在押。生效后这条抵押开始拦截处置 / 流转 / 调拨，
     * 且**不可撤回**（要解除只能走解押审批），前端需二次确认。
     */
    @PostMapping("/{id}/effect")
    @RequiresPerm("deed.mortgage:update")
    @Audited(module = "mortgage", action = "effect")
    public ApiResponse<MortgageRecordView> effect(@PathVariable Long id) {
        return ApiResponse.ok(service.effect(id), TraceIdUtil.get());
    }
}
