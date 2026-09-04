package com.ams.platform.health;

import com.ams.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, String>>> health(HttpServletRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(Map.of("status", "ok"), traceId(request)));
    }

    @GetMapping("/live")
    public ResponseEntity<ApiResponse<Map<String, String>>> live(HttpServletRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(Map.of("status", "live"), traceId(request)));
    }

    @GetMapping("/ready")
    public ResponseEntity<ApiResponse<Map<String, String>>> ready(HttpServletRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(Map.of("status", "ready"), traceId(request)));
    }

    private static String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute("traceId");
        return traceId != null ? traceId.toString() : "";
    }
}
