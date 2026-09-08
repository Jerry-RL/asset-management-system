package com.ams.modules.io.service;

import com.ams.common.csv.CsvUtf8;
import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.service.TenantService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通用导入导出（CSV）：资产台账、账单。
 */
@Service
public class ImportExportService {

    private final AssetMapper assetMapper;
    private final BillMapper billMapper;
    private final TenantService tenantService;

    public ImportExportService(AssetMapper assetMapper, BillMapper billMapper, TenantService tenantService) {
        this.assetMapper = assetMapper;
        this.billMapper = billMapper;
        this.tenantService = tenantService;
    }

    public String exportAssetsCsv(Long projectId) {
        List<Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>()
                        .eq(projectId != null, Asset::getProjectId, projectId)
                        .orderByAsc(Asset::getId));
        StringBuilder sb = new StringBuilder();
        sb.append("assetNo,name,assetType,area,leaseControlStatus,projectId,address,province,city,district,baseRentFloor\n");
        for (Asset a : assets) {
            sb.append(csv(a.getAssetNo())).append(',')
                    .append(csv(a.getName())).append(',')
                    .append(csv(a.getAssetType())).append(',')
                    .append(a.getArea() == null ? "" : a.getArea()).append(',')
                    .append(csv(a.getLeaseControlStatus())).append(',')
                    .append(a.getProjectId() == null ? "" : a.getProjectId()).append(',')
                    .append(csv(a.getAddress())).append(',')
                    .append(csv(a.getProvince())).append(',')
                    .append(csv(a.getCity())).append(',')
                    .append(csv(a.getDistrict())).append(',')
                    .append(a.getBaseRentFloor() == null ? "" : a.getBaseRentFloor())
                    .append('\n');
        }
        return sb.toString();
    }

    public String exportBillsCsv(Long contractId) {
        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .eq(contractId != null, Bill::getContractId, contractId)
                        .orderByDesc(Bill::getId));
        StringBuilder sb = new StringBuilder();
        sb.append("billNo,contractId,billType,dueDate,amount,paidAmount,lateFeeAmount,status\n");
        for (Bill b : bills) {
            sb.append(csv(b.getBillNo())).append(',')
                    .append(b.getContractId()).append(',')
                    .append(csv(b.getBillType())).append(',')
                    .append(b.getDueDate()).append(',')
                    .append(b.getAmount()).append(',')
                    .append(b.getPaidAmount()).append(',')
                    .append(b.getLateFeeAmount()).append(',')
                    .append(csv(b.getStatus())).append('\n');
        }
        return sb.toString();
    }

    @Transactional
    public Map<String, Object> importAssetsCsv(MultipartFile file) {
        List<String> errors = new ArrayList<>();
        int success = 0;
        try (InputStream in = file.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = CsvUtf8.stripBom(reader.readLine());
            if (header == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "空文件");
            }
            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    String[] cols = splitCsv(CsvUtf8.stripBom(line));
                    Asset asset = new Asset();
                    asset.setAssetNo(cols[0]);
                    asset.setName(cols.length > 1 ? cols[1] : cols[0]);
                    asset.setAssetType(cols.length > 2 && !cols[2].isBlank() ? cols[2] : "property");
                    if (cols.length > 3 && !cols[3].isBlank()) {
                        asset.setArea(new BigDecimal(cols[3]));
                    }
                    asset.setLeaseControlStatus(cols.length > 4 && !cols[4].isBlank()
                            ? cols[4] : LeaseControlStatus.VACANT);
                    if (cols.length > 5 && !cols[5].isBlank()) {
                        asset.setProjectId(Long.valueOf(cols[5]));
                    }
                    if (cols.length > 6) {
                        asset.setAddress(cols[6]);
                    }
                    if (cols.length > 7) {
                        asset.setProvince(cols[7]);
                    }
                    if (cols.length > 8) {
                        asset.setCity(cols[8]);
                    }
                    if (cols.length > 9) {
                        asset.setDistrict(cols[9]);
                    }
                    if (cols.length > 10 && !cols[10].isBlank()) {
                        asset.setBaseRentFloor(new BigDecimal(cols[10]));
                    }
                    Long exists = assetMapper.selectCount(
                            new LambdaQueryWrapper<Asset>().eq(Asset::getAssetNo, asset.getAssetNo()));
                    if (exists != null && exists > 0) {
                        errors.add("L" + lineNo + ": 资产编号已存在 " + asset.getAssetNo());
                        continue;
                    }
                    assetMapper.insert(asset);
                    success++;
                } catch (Exception e) {
                    errors.add("L" + lineNo + ": " + e.getMessage());
                }
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "导入失败: " + e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("failed", errors.size());
        result.put("errors", errors);
        return result;
    }

    @Transactional
    public Map<String, Object> importTenantsCsv(MultipartFile file) {
        List<String> errors = new ArrayList<>();
        int success = 0;
        try (InputStream in = file.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            CsvUtf8.stripBom(reader.readLine()); // header（兼容 Excel UTF-8 BOM）
            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    String[] cols = splitCsv(CsvUtf8.stripBom(line));
                    Tenant t = new Tenant();
                    t.setName(cols[0]);
                    t.setPhone(cols.length > 1 ? cols[1] : null);
                    t.setIdNo(cols.length > 2 ? cols[2] : null);
                    t.setTenantType(cols.length > 3 && !cols[3].isBlank() ? cols[3] : "person");
                    tenantService.create(t);
                    success++;
                } catch (Exception e) {
                    errors.add("L" + lineNo + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "导入失败: " + e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("failed", errors.size());
        result.put("errors", errors);
        return result;
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        return v.replace(",", " ").replace("\n", " ");
    }

    private static String[] splitCsv(String line) {
        return line.split(",", -1);
    }
}
