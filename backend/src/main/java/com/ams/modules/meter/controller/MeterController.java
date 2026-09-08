package com.ams.modules.meter.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.meter.entity.Meter;
import com.ams.modules.meter.entity.MeterReading;
import com.ams.modules.meter.mapper.MeterMapper;
import com.ams.modules.meter.mapper.MeterReadingMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表计档案与抄表接口（FR-UTIL-001/002、FR-MPW-009 抄表）。
 */
@RestController
@RequestMapping("/api/v1")
public class MeterController {

    private final MeterMapper meterMapper;
    private final MeterReadingMapper readingMapper;

    public MeterController(MeterMapper meterMapper, MeterReadingMapper readingMapper) {
        this.meterMapper = meterMapper;
        this.readingMapper = readingMapper;
    }

    @GetMapping("/meters")
    public ApiResponse<List<Meter>> meters(@RequestParam(required = false) Long assetId) {
        return ApiResponse.ok(meterMapper.selectList(
                new LambdaQueryWrapper<Meter>().eq(assetId != null, Meter::getAssetId, assetId)),
                TraceIdUtil.get());
    }

    @PostMapping("/meters")
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
    public ApiResponse<MeterReading> recordReading(@PathVariable Long meterId, @RequestBody MeterReading reading) {
        reading.setId(null);
        reading.setMeterId(meterId);
        reading.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        reading.setCreatedAt(LocalDateTime.now());
        readingMapper.insert(reading);
        return ApiResponse.ok(reading, TraceIdUtil.get());
    }
}
