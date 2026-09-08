package com.ams.modules.opscalendar.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.opscalendar.dto.OpsCalendarEvent;
import com.ams.modules.opscalendar.entity.OpsCalendarNote;
import com.ams.modules.opscalendar.service.OpsCalendarService;
import com.ams.platform.security.Audited;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 经营日历接口：聚合运营关键日事项，支撑首页待办与运营排期。
 */
@RestController
@RequestMapping("/api/v1/ops-calendar")
public class OpsCalendarController {

    private final OpsCalendarService opsCalendarService;

    public OpsCalendarController(OpsCalendarService opsCalendarService) {
        this.opsCalendarService = opsCalendarService;
    }

    @GetMapping("/events")
    public ApiResponse<List<OpsCalendarEvent>> events(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String types) {
        return ApiResponse.ok(opsCalendarService.listEvents(from, to, types), TraceIdUtil.get());
    }

    @GetMapping("/day-summary")
    public ApiResponse<Map<String, Object>> daySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String types) {
        return ApiResponse.ok(opsCalendarService.daySummary(from, to, types), TraceIdUtil.get());
    }

    @GetMapping("/upcoming")
    public ApiResponse<List<OpsCalendarEvent>> upcoming(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String types) {
        return ApiResponse.ok(opsCalendarService.upcoming(days, types), TraceIdUtil.get());
    }

    @PostMapping("/notes")
    @Audited(module = "ops-calendar", action = "create_note")
    public ApiResponse<OpsCalendarNote> createNote(@RequestBody OpsCalendarNote note) {
        return ApiResponse.ok(opsCalendarService.createNote(note), TraceIdUtil.get());
    }

    @DeleteMapping("/notes/{id}")
    @Audited(module = "ops-calendar", action = "delete_note")
    public ApiResponse<Void> deleteNote(@PathVariable Long id) {
        opsCalendarService.deleteNote(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }
}
