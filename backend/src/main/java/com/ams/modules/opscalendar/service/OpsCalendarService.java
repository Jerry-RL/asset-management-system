package com.ams.modules.opscalendar.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.mapper.MortgageMapper;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.maintenance.entity.InspectionRecord;
import com.ams.modules.maintenance.entity.RepairOrder;
import com.ams.modules.maintenance.mapper.InspectionRecordMapper;
import com.ams.modules.maintenance.mapper.RepairOrderMapper;
import com.ams.modules.occupation.entity.OccupationOrder;
import com.ams.modules.occupation.mapper.OccupationOrderMapper;
import com.ams.modules.opscalendar.dto.OpsCalendarEvent;
import com.ams.modules.opscalendar.entity.OpsCalendarNote;
import com.ams.modules.opscalendar.mapper.OpsCalendarNoteMapper;
import com.ams.modules.selfuse.entity.SelfUseOrder;
import com.ams.modules.selfuse.mapper.SelfUseOrderMapper;
import com.ams.modules.task.entity.Task;
import com.ams.modules.task.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 经营日历：聚合合同到期、账单应收、任务截止、抵押/占用/自用到期、巡检、报修 SLA 及手工提醒。
 */
@Service
public class OpsCalendarService {

    public static final Set<String> ALL_TYPES = Set.of(
            "contract_expiry",
            "bill_due",
            "task_deadline",
            "mortgage_expiry",
            "occupation_end",
            "self_use_end",
            "inspection",
            "repair_sla",
            "note");

    private static final Set<String> CONTRACT_WATCH =
            Set.of("active", "renewable", "expired", "approving");
    private static final Set<String> BILL_OPEN = Set.of("unpaid", "partial_paid", "pending_issue");
    private static final Set<String> REPAIR_OPEN =
            Set.of("pending_review", "dispatched", "accepted", "repairing", "pending_accept");

    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final TaskMapper taskMapper;
    private final MortgageMapper mortgageMapper;
    private final OccupationOrderMapper occupationOrderMapper;
    private final SelfUseOrderMapper selfUseOrderMapper;
    private final InspectionRecordMapper inspectionRecordMapper;
    private final RepairOrderMapper repairOrderMapper;
    private final OpsCalendarNoteMapper noteMapper;

    public OpsCalendarService(
            ContractMapper contractMapper,
            BillMapper billMapper,
            TaskMapper taskMapper,
            MortgageMapper mortgageMapper,
            OccupationOrderMapper occupationOrderMapper,
            SelfUseOrderMapper selfUseOrderMapper,
            InspectionRecordMapper inspectionRecordMapper,
            RepairOrderMapper repairOrderMapper,
            OpsCalendarNoteMapper noteMapper) {
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.taskMapper = taskMapper;
        this.mortgageMapper = mortgageMapper;
        this.occupationOrderMapper = occupationOrderMapper;
        this.selfUseOrderMapper = selfUseOrderMapper;
        this.inspectionRecordMapper = inspectionRecordMapper;
        this.repairOrderMapper = repairOrderMapper;
        this.noteMapper = noteMapper;
    }

    public List<OpsCalendarEvent> listEvents(LocalDate from, LocalDate to, String typesCsv) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : start.withDayOfMonth(start.lengthOfMonth());
        if (end.isBefore(start)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "结束日期不能早于开始日期");
        }
        Set<String> types = parseTypes(typesCsv);
        List<OpsCalendarEvent> events = new ArrayList<>();
        if (types.contains("contract_expiry")) {
            events.addAll(collectContracts(start, end));
        }
        if (types.contains("bill_due")) {
            events.addAll(collectBills(start, end));
        }
        if (types.contains("task_deadline")) {
            events.addAll(collectTasks(start, end));
        }
        if (types.contains("mortgage_expiry")) {
            events.addAll(collectMortgages(start, end));
        }
        if (types.contains("occupation_end")) {
            events.addAll(collectOccupations(start, end));
        }
        if (types.contains("self_use_end")) {
            events.addAll(collectSelfUses(start, end));
        }
        if (types.contains("inspection")) {
            events.addAll(collectInspections(start, end));
        }
        if (types.contains("repair_sla")) {
            events.addAll(collectRepairs(start, end));
        }
        if (types.contains("note")) {
            events.addAll(collectNotes(start, end));
        }
        events.sort(Comparator
                .comparing(OpsCalendarEvent::getEventDate)
                .thenComparing(OpsCalendarEvent::getLevel, Comparator.reverseOrder())
                .thenComparing(OpsCalendarEvent::getType));
        return events;
    }

    /** 按日汇总：日历打点用。 */
    public Map<String, Object> daySummary(LocalDate from, LocalDate to, String typesCsv) {
        List<OpsCalendarEvent> events = listEvents(from, to, typesCsv);
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (OpsCalendarEvent e : events) {
            String day = e.getEventDate().toString();
            Map<String, Object> cell = byDay.computeIfAbsent(day, d -> {
                Map<String, Object> m = new HashMap<>();
                m.put("date", d);
                m.put("total", 0);
                m.put("high", 0);
                m.put("byType", new HashMap<String, Integer>());
                return m;
            });
            cell.put("total", ((Integer) cell.get("total")) + 1);
            if (e.getLevel() >= 3) {
                cell.put("high", ((Integer) cell.get("high")) + 1);
            }
            @SuppressWarnings("unchecked")
            Map<String, Integer> byType = (Map<String, Integer>) cell.get("byType");
            byType.merge(e.getType(), 1, Integer::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", (from != null ? from : LocalDate.now().withDayOfMonth(1)).toString());
        result.put("to", (to != null ? to : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth())).toString());
        result.put("days", new ArrayList<>(byDay.values()));
        result.put("total", events.size());
        return result;
    }

    public List<OpsCalendarEvent> upcoming(int days, String typesCsv) {
        int d = Math.max(0, Math.min(days, 90));
        LocalDate today = LocalDate.now();
        return listEvents(today, today.plusDays(d), typesCsv);
    }

    @Transactional
    public OpsCalendarNote createNote(OpsCalendarNote note) {
        if (note.getEventDate() == null || !StringUtils.hasText(note.getTitle())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "日期与标题不能为空");
        }
        if (note.getLevel() == null) {
            note.setLevel(2);
        }
        noteMapper.insert(note);
        return note;
    }

    @Transactional
    public void deleteNote(Long id) {
        if (noteMapper.selectById(id) == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "提醒不存在");
        }
        noteMapper.deleteById(id);
    }

    private List<OpsCalendarEvent> collectContracts(LocalDate from, LocalDate to) {
        List<Contract> list = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .between(Contract::getEndDate, from, to)
                .in(Contract::getStatus, CONTRACT_WATCH));
        List<OpsCalendarEvent> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Contract c : list) {
            boolean overdue = c.getEndDate().isBefore(today);
            out.add(OpsCalendarEvent.builder()
                    .id("contract_expiry:" + c.getId())
                    .type("contract_expiry")
                    .title("合同到期 · " + nullToDash(c.getContractNo()))
                    .summary("状态 " + c.getStatus() + "，租期至 " + c.getEndDate())
                    .eventDate(c.getEndDate())
                    .level(overdue || "expired".equals(c.getStatus()) || "renewable".equals(c.getStatus()) ? 3 : 2)
                    .status(c.getStatus())
                    .bizType("contract")
                    .bizId(c.getId())
                    .linkPath("/contracts/" + c.getId())
                    .build());
        }
        return out;
    }

    private List<OpsCalendarEvent> collectBills(LocalDate from, LocalDate to) {
        List<Bill> list = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .between(Bill::getDueDate, from, to)
                .in(Bill::getStatus, BILL_OPEN));
        List<OpsCalendarEvent> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Bill b : list) {
            boolean overdue = b.getDueDate().isBefore(today);
            out.add(OpsCalendarEvent.builder()
                    .id("bill_due:" + b.getId())
                    .type("bill_due")
                    .title("账单应收 · " + nullToDash(b.getBillNo()))
                    .summary("金额 " + b.getAmount() + "，状态 " + b.getStatus())
                    .eventDate(b.getDueDate())
                    .level(overdue ? 3 : 2)
                    .status(b.getStatus())
                    .bizType("bill")
                    .bizId(b.getId())
                    .linkPath("/billing/bills")
                    .build());
        }
        return out;
    }

    private List<OpsCalendarEvent> collectTasks(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.atTime(LocalTime.MAX);
        List<Task> list = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getStatus, "pending")
                .isNotNull(Task::getDeadline)
                .between(Task::getDeadline, start, end));
        List<OpsCalendarEvent> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Task t : list) {
            boolean overdue = t.getDeadline().isBefore(now);
            out.add(OpsCalendarEvent.builder()
                    .id("task_deadline:" + t.getId())
                    .type("task_deadline")
                    .title("任务截止 · " + nullToDash(t.getTaskType()))
                    .summary(t.getRefNo() != null ? "关联单号 " + t.getRefNo() : "待办任务 #" + t.getId())
                    .eventDate(t.getDeadline().toLocalDate())
                    .level(overdue ? 3 : 2)
                    .status(t.getStatus())
                    .bizType("task")
                    .bizId(t.getId())
                    .linkPath("/tasks")
                    .build());
        }
        return out;
    }

    private List<OpsCalendarEvent> collectMortgages(LocalDate from, LocalDate to) {
        List<Mortgage> list = mortgageMapper.selectList(new LambdaQueryWrapper<Mortgage>()
                .eq(Mortgage::getStatus, "active")
                .between(Mortgage::getEndDate, from, to));
        List<OpsCalendarEvent> out = new ArrayList<>();
        for (Mortgage m : list) {
            out.add(OpsCalendarEvent.builder()
                    .id("mortgage_expiry:" + m.getId())
                    .type("mortgage_expiry")
                    .title("抵押到期 · " + nullToDash(m.getMortgagee()))
                    .summary("金额 " + m.getAmount() + "，资产 #" + m.getAssetId())
                    .eventDate(m.getEndDate())
                    .level(3)
                    .status(m.getStatus())
                    .bizType("mortgage")
                    .bizId(m.getId())
                    .linkPath("/mortgages")
                    .build());
        }
        return out;
    }

    private List<OpsCalendarEvent> collectOccupations(LocalDate from, LocalDate to) {
        List<OccupationOrder> list = occupationOrderMapper.selectList(new LambdaQueryWrapper<OccupationOrder>()
                .eq(OccupationOrder::getStatus, "occupied")
                .between(OccupationOrder::getEndDate, from, to));
        List<OpsCalendarEvent> out = new ArrayList<>();
        for (OccupationOrder o : list) {
            out.add(OpsCalendarEvent.builder()
                    .id("occupation_end:" + o.getId())
                    .type("occupation_end")
                    .title("临时占用到期")
                    .summary(nullToDash(o.getReason()) + " · 资产 #" + o.getAssetId())
                    .eventDate(o.getEndDate())
                    .level(2)
                    .status(o.getStatus())
                    .bizType("occupation")
                    .bizId(o.getId())
                    .linkPath("/occupations")
                    .build());
        }
        return out;
    }

    private List<OpsCalendarEvent> collectSelfUses(LocalDate from, LocalDate to) {
        List<SelfUseOrder> list = selfUseOrderMapper.selectList(new LambdaQueryWrapper<SelfUseOrder>()
                .eq(SelfUseOrder::getStatus, "self_use")
                .between(SelfUseOrder::getEndDate, from, to));
        List<OpsCalendarEvent> out = new ArrayList<>();
        for (SelfUseOrder o : list) {
            out.add(OpsCalendarEvent.builder()
                    .id("self_use_end:" + o.getId())
                    .type("self_use_end")
                    .title("自用到期")
                    .summary(nullToDash(o.getPurpose()) + " · " + nullToDash(o.getDepartment()))
                    .eventDate(o.getEndDate())
                    .level(2)
                    .status(o.getStatus())
                    .bizType("self_use")
                    .bizId(o.getId())
                    .linkPath("/self-uses")
                    .build());
        }
        return out;
    }

    private List<OpsCalendarEvent> collectInspections(LocalDate from, LocalDate to) {
        List<InspectionRecord> list = inspectionRecordMapper.selectList(new LambdaQueryWrapper<InspectionRecord>()
                .eq(InspectionRecord::getStatus, "pending")
                .between(InspectionRecord::getPlanDate, from, to));
        List<OpsCalendarEvent> out = new ArrayList<>();
        for (InspectionRecord r : list) {
            out.add(OpsCalendarEvent.builder()
                    .id("inspection:" + r.getId())
                    .type("inspection")
                    .title("巡检计划")
                    .summary("资产 #" + r.getAssetId())
                    .eventDate(r.getPlanDate())
                    .level(2)
                    .status(r.getStatus())
                    .bizType("inspection")
                    .bizId(r.getId())
                    .linkPath("/inspections")
                    .build());
        }
        return out;
    }

    private List<OpsCalendarEvent> collectRepairs(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.atTime(LocalTime.MAX);
        List<RepairOrder> list = repairOrderMapper.selectList(new LambdaQueryWrapper<RepairOrder>()
                .in(RepairOrder::getStatus, REPAIR_OPEN)
                .and(w -> w.between(RepairOrder::getSlaCompleteDeadline, start, end)
                        .or()
                        .between(RepairOrder::getSlaResponseDeadline, start, end)));
        List<OpsCalendarEvent> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (RepairOrder r : list) {
            LocalDateTime deadline = r.getSlaCompleteDeadline() != null
                    ? r.getSlaCompleteDeadline()
                    : r.getSlaResponseDeadline();
            if (deadline == null) {
                continue;
            }
            LocalDate day = deadline.toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) {
                continue;
            }
            boolean overdue = Boolean.TRUE.equals(r.getSlaBreached()) || deadline.isBefore(now);
            out.add(OpsCalendarEvent.builder()
                    .id("repair_sla:" + r.getId())
                    .type("repair_sla")
                    .title("报修 SLA · #" + r.getId())
                    .summary(nullToDash(r.getDescription()))
                    .eventDate(day)
                    .level(overdue ? 3 : 2)
                    .status(r.getStatus())
                    .bizType("repair")
                    .bizId(r.getId())
                    .linkPath("/repairs")
                    .build());
        }
        return out;
    }

    private List<OpsCalendarEvent> collectNotes(LocalDate from, LocalDate to) {
        List<OpsCalendarNote> list = noteMapper.selectList(new LambdaQueryWrapper<OpsCalendarNote>()
                .between(OpsCalendarNote::getEventDate, from, to));
        List<OpsCalendarEvent> out = new ArrayList<>();
        for (OpsCalendarNote n : list) {
            out.add(OpsCalendarEvent.builder()
                    .id("note:" + n.getId())
                    .type("note")
                    .title(n.getTitle())
                    .summary(n.getContent())
                    .eventDate(n.getEventDate())
                    .level(n.getLevel() == null ? 2 : n.getLevel())
                    .status("note")
                    .bizType("note")
                    .bizId(n.getId())
                    .linkPath("/ops-calendar")
                    .build());
        }
        return out;
    }

    private Set<String> parseTypes(String typesCsv) {
        if (!StringUtils.hasText(typesCsv)) {
            return new HashSet<>(ALL_TYPES);
        }
        return Arrays.stream(typesCsv.split(","))
                .map(String::trim)
                .filter(ALL_TYPES::contains)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static String nullToDash(String s) {
        return StringUtils.hasText(s) ? s : "-";
    }
}
