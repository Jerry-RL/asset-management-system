package com.ams.modules.meter.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.meter.entity.Meter;
import com.ams.modules.meter.entity.MeterReading;
import com.ams.modules.meter.mapper.MeterMapper;
import com.ams.modules.meter.mapper.MeterReadingMapper;
import com.ams.modules.meter.service.UtilityBillingService;
import com.ams.platform.security.Audited;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表计档案与抄表出账（FR-UTIL-*、FR-MPW-009）。
 */
@RestController
@RequestMapping("/api/v1")
public class MeterController {

    private final MeterMapper meterMapper;
    private final MeterReadingMapper readingMapper;
    private final UtilityBillingService utilityBillingService;

    public MeterController(
            MeterMapper meterMapper,
            MeterReadingMapper readingMapper,
            UtilityBillingService utilityBillingService) {
        this.meterMapper = meterMapper;
        this.readingMapper = readingMapper;
        this.utilityBillingService = utilityBillingService;
    }

    @GetMapping("/meters")
    public ApiResponse<List<Meter>> meters(@RequestParam(required = false) Long assetId) {
        return ApiResponse.ok(meterMapper.selectList(
                new LambdaQueryWrapper<Meter>().eq(assetId != null, Meter::getAssetId, assetId)),
                TraceIdUtil.get());
    }

    @PostMapping("/meters")
    @Audited(module = "meter", action = "create")
    public ApiResponse<Meter> createMeter(@RequestBody Meter meter) {
        meterMapper.insert(meter);
        return ApiResponse.ok(meter, TraceIdUtil.get());
    }

    @GetMapping("/meters/{meterId}/readings")
    public ApiResponse<List<MeterReading>> readings(@PathVariable Long meterId) {
        return ApiResponse.ok(readingMapper.selectList(
                new LambdaQueryWrapper<MeterReading>()
                        .eq(MeterReading::getMeterId, meterId)
                        .orderByDesc(MeterReading::getReadingDate)),
                TraceIdUtil.get());
    }

    @PostMapping("/meters/{meterId}/readings")
    @Audited(module = "meter", action = "reading")
    public ApiResponse<MeterReading> recordReading(
            @PathVariable Long meterId, @RequestBody Map<String, Object> body) {
        MeterReading reading = new MeterReading();
        if (body.get("reading") != null) {
            reading.setReading(new java.math.BigDecimal(body.get("reading").toString()));
        }
        if (body.get("usage") != null) {
            reading.setUsage(new java.math.BigDecimal(body.get("usage").toString()));
        }
        if (body.get("readingDate") != null) {
            reading.setReadingDate(java.time.LocalDate.parse(body.get("readingDate").toString()));
        }
        boolean generateBill = body.get("generateBill") == null
                || Boolean.parseBoolean(body.get("generateBill").toString());
        return ApiResponse.ok(utilityBillingService.recordReadingAndMaybeBill(meterId, reading, generateBill),
                TraceIdUtil.get());
    }

    @PostMapping("/meters/utility/issue")
    @Audited(module = "meter", action = "issue_utility")
    public ApiResponse<Integer> issueUtility() {
        return ApiResponse.ok(utilityBillingService.issuePendingUtilityBills(), TraceIdUtil.get());
    }
}
