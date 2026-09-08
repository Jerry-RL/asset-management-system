package com.ams.modules.meter.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.meter.entity.Meter;
import com.ams.modules.meter.entity.MeterReading;
import com.ams.modules.meter.entity.UtilityBill;
import com.ams.modules.meter.mapper.MeterMapper;
import com.ams.modules.meter.mapper.MeterReadingMapper;
import com.ams.modules.meter.mapper.UtilityBillMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 水电抄表出账（FR-UTIL-*）：读数差额 → 账单 + utility_bill；公摊表走 ApportionService。
 */
@Service
public class UtilityBillingService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MeterMapper meterMapper;
    private final MeterReadingMapper readingMapper;
    private final UtilityBillMapper utilityBillMapper;
    private final BillMapper billMapper;
    private final ContractMapper contractMapper;
    private final AssetMapper assetMapper;
    private final ApportionService apportionService;
    private final JdbcTemplate jdbcTemplate;

    public UtilityBillingService(
            MeterMapper meterMapper,
            MeterReadingMapper readingMapper,
            UtilityBillMapper utilityBillMapper,
            BillMapper billMapper,
            ContractMapper contractMapper,
            AssetMapper assetMapper,
            ApportionService apportionService,
            JdbcTemplate jdbcTemplate) {
        this.meterMapper = meterMapper;
        this.readingMapper = readingMapper;
        this.utilityBillMapper = utilityBillMapper;
        this.billMapper = billMapper;
        this.contractMapper = contractMapper;
        this.assetMapper = assetMapper;
        this.apportionService = apportionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public MeterReading recordReadingAndMaybeBill(Long meterId, MeterReading reading, boolean generateBill) {
        Meter meter = meterMapper.selectById(meterId);
        if (meter == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "表计不存在");
        }
        MeterReading last = readingMapper.selectOne(
                new LambdaQueryWrapper<MeterReading>()
                        .eq(MeterReading::getMeterId, meterId)
                        .orderByDesc(MeterReading::getReadingDate)
                        .orderByDesc(MeterReading::getId)
                        .last("LIMIT 1"));
        BigDecimal usage = reading.getUsage();
        if (usage == null && last != null && reading.getReading() != null) {
            usage = reading.getReading().subtract(last.getReading());
            if (usage.compareTo(BigDecimal.ZERO) < 0) {
                throw new AppException(ErrorCode.BAD_REQUEST, "本次读数小于上次读数");
            }
        }
        if (usage == null) {
            usage = BigDecimal.ZERO;
        }
        BigDecimal multiplier = meter.getMultiplier() == null ? BigDecimal.ONE : meter.getMultiplier();
        usage = usage.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);

        reading.setId(null);
        reading.setMeterId(meterId);
        reading.setUsage(usage);
        if (reading.getReadingDate() == null) {
            reading.setReadingDate(LocalDate.now());
        }
        reading.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        reading.setCreatedAt(LocalDateTime.now());
        readingMapper.insert(reading);

        if (generateBill && usage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal unitPrice = resolveUnitPrice(meter);
            BigDecimal amount = usage.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
            if (Boolean.TRUE.equals(meter.getShared()) && meter.getAssetId() != null) {
                Asset asset = assetMapper.selectById(meter.getAssetId());
                if (asset != null && asset.getProjectId() != null) {
                    apportionService.computeApportion(asset.getProjectId(), amount, null);
                } else if (meter.getContractId() != null) {
                    generateUtilityBill(meter, usage, reading.getReadingDate());
                }
            } else if (meter.getContractId() != null) {
                generateUtilityBill(meter, usage, reading.getReadingDate());
            }
        }
        return reading;
    }

    @Transactional
    public Bill generateUtilityBill(Meter meter, BigDecimal usage, LocalDate dueDate) {
        BigDecimal unitPrice = resolveUnitPrice(meter);
        BigDecimal amount = usage.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        Contract contract = contractMapper.selectById(meter.getContractId());

        Bill bill = new Bill();
        bill.setBillNo("SF" + LocalDate.now().format(NO_FMT)
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        bill.setContractId(meter.getContractId());
        bill.setAssetId(meter.getAssetId());
        bill.setTenantId(contract == null ? null : contract.getTenantId());
        bill.setBillType("utility");
        bill.setPeriodStart(dueDate == null ? LocalDate.now().withDayOfMonth(1) : dueDate.withDayOfMonth(1));
        bill.setPeriodEnd(dueDate == null ? LocalDate.now() : dueDate);
        bill.setDueDate(dueDate == null ? LocalDate.now().plusDays(7) : dueDate.plusDays(7));
        bill.setAmount(amount);
        bill.setPaidAmount(BigDecimal.ZERO);
        bill.setLateFeeAmount(BigDecimal.ZERO);
        bill.setLateFeePaidAmount(BigDecimal.ZERO);
        bill.setStatus(BillStatus.UNPAID);
        bill.setDunningLevel(0);
        bill.setSource("system");
        billMapper.insert(bill);

        UtilityBill ub = new UtilityBill();
        ub.setBillId(bill.getId());
        ub.setContractId(meter.getContractId());
        ub.setMeterId(meter.getId());
        ub.setUsage(usage);
        ub.setUnitPrice(unitPrice);
        ub.setApportionAmount(BigDecimal.ZERO);
        ub.setAmount(amount);
        ub.setCreatedAt(LocalDateTime.now());
        utilityBillMapper.insert(ub);
        return bill;
    }

    /** 按合同批量生成未出账抄表账单（最近一条未挂 bill 的读数）。 */
    @Transactional
    public int issuePendingUtilityBills() {
        List<Meter> meters = meterMapper.selectList(
                new LambdaQueryWrapper<Meter>().isNotNull(Meter::getContractId).eq(Meter::getStatus, 1));
        int n = 0;
        for (Meter meter : meters) {
            if (Boolean.TRUE.equals(meter.getShared())) {
                continue;
            }
            MeterReading last = readingMapper.selectOne(
                    new LambdaQueryWrapper<MeterReading>()
                            .eq(MeterReading::getMeterId, meter.getId())
                            .orderByDesc(MeterReading::getReadingDate)
                            .last("LIMIT 1"));
            if (last == null || last.getUsage() == null || last.getUsage().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Long exists = utilityBillMapper.selectCount(
                    new LambdaQueryWrapper<UtilityBill>()
                            .eq(UtilityBill::getMeterId, meter.getId())
                            .eq(UtilityBill::getUsage, last.getUsage())
                            .ge(UtilityBill::getCreatedAt, LocalDate.now().withDayOfMonth(1).atStartOfDay()));
            if (exists != null && exists > 0) {
                continue;
            }
            generateUtilityBill(meter, last.getUsage(), last.getReadingDate());
            n++;
        }
        return n;
    }

    private BigDecimal resolveUnitPrice(Meter meter) {
        String type = meter.getMeterType() == null ? "water" : meter.getMeterType();
        try {
            Map<String, Object> cfg = jdbcTemplate.query(
                    "SELECT unit_price_water, unit_price_electric, unit_price_gas FROM apportion_config WHERE enabled = TRUE ORDER BY id LIMIT 1",
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        Map<String, Object> m = new HashMap<>();
                        m.put("water", rs.getBigDecimal("unit_price_water"));
                        m.put("electric", rs.getBigDecimal("unit_price_electric"));
                        m.put("gas", rs.getBigDecimal("unit_price_gas"));
                        return m;
                    });
            if (cfg != null) {
                Object v = cfg.get(type);
                if (v instanceof BigDecimal bd && bd.compareTo(BigDecimal.ZERO) > 0) {
                    return bd;
                }
            }
        } catch (Exception ignored) {
            // 列可能尚未迁移
        }
        return switch (type) {
            case "electric" -> new BigDecimal("0.80");
            case "gas" -> new BigDecimal("2.50");
            default -> new BigDecimal("3.50");
        };
    }
}
