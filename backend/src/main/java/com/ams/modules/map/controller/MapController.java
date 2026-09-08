package com.ams.modules.map.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.config.AmsProperties;
import com.ams.modules.map.service.MapService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map")
public class MapController {

    private final MapService mapService;
    private final AmsProperties amsProperties;

    public MapController(MapService mapService, AmsProperties amsProperties) {
        this.mapService = mapService;
        this.amsProperties = amsProperties;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("provider", amsProperties.getMap().isEnabled() ? "amap" : "canvas");
        cfg.put("amapKey", amsProperties.getMap().getAmapKey());
        cfg.put("amapSecurityCode", amsProperties.getMap().getAmapSecurityCode());
        return ApiResponse.ok(cfg, TraceIdUtil.get());
    }

    @GetMapping("/points")
    public ApiResponse<List<Map<String, Object>>> points(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) String city) {
        return ApiResponse.ok(mapService.points(companyId, province, city), TraceIdUtil.get());
    }
}
