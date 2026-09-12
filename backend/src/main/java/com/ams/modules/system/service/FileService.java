package com.ams.modules.system.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.system.entity.FileMetadata;
import com.ams.modules.system.mapper.FileMetadataMapper;
import com.ams.platform.security.SecurityUtils;
import com.ams.platform.storage.ObjectStorageClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件上传（ADR-0007 / OpenAPI POST /files/upload）。
 */
@Service
public class FileService {

    private final FileMetadataMapper fileMetadataMapper;
    private final ObjectStorageClient objectStorageClient;

    public FileService(FileMetadataMapper fileMetadataMapper, ObjectStorageClient objectStorageClient) {
        this.fileMetadataMapper = fileMetadataMapper;
        this.objectStorageClient = objectStorageClient;
    }

    @Transactional
    public FileMetadata upload(MultipartFile file, String bizType) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请选择文件");
        }
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String objectKey = LocalDate.now() + "/" + UUID.randomUUID().toString().replace("-", "")
                + "_" + sanitize(original);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = file.getInputStream();
                    DigestInputStream din = new DigestInputStream(in, digest)) {
                ObjectStorageClient.StoredObject stored = objectStorageClient.put(
                        objectKey, din, file.getSize(), file.getContentType());
                FileMetadata meta = new FileMetadata();
                meta.setBucket(stored.bucket());
                meta.setObjectKey(stored.objectKey());
                meta.setFileName(original);
                meta.setContentType(file.getContentType());
                meta.setHashSha256(HexFormat.of().formatHex(digest.digest()));
                meta.setSize(stored.size());
                meta.setBizType(bizType == null || bizType.isBlank() ? "general" : bizType);
                meta.setCreatedBy(SecurityUtils.currentUserIdOrNull());
                meta.setCreatedAt(LocalDateTime.now());
                fileMetadataMapper.insert(meta);
                return meta;
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "上传失败: " + e.getMessage());
        }
    }

    public FileMetadata get(Long id) {
        FileMetadata meta = fileMetadataMapper.selectById(id);
        if (meta == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return meta;
    }

    /**
     * 校验这批 fileId 可被当前用户在本系统内使用（设计 §4.3 的附件归属口径）。
     *
     * <p>规则：id 必须存在；且上传者是当前用户本人，或 {@code created_by} 为空（历史/种子文件 ——
     * 应用启动时的 seed 没有安全上下文，{@code created_by} 天然为 null，允许它们被引用）。
     *
     * <p>**只校验「新挂上来的」fileId**，调用方负责滤掉已经挂在该宿主上的旧引用：
     * 否则一条指向已清理 file_metadata 的存量附件会让整个 record-sheet **再也保存不了**
     * （§4.3 明确本期不做孤儿文件清理，存量脏引用必须可继续原样提交）。
     *
     * <p>无安全上下文（{@code currentUserIdOrNull()} 为 null）时只校验存在性、跳过归属比较：
     * record-sheet 的写入只能经鉴权控制器到达，系统内部调用不按人归属。
     */
    public void assertAttachable(Collection<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        Map<Long, FileMetadata> found = fileMetadataMapper.selectBatchIds(fileIds).stream()
                .collect(Collectors.toMap(FileMetadata::getId, Function.identity()));
        Long currentUserId = SecurityUtils.currentUserIdOrNull();
        for (Long fileId : fileIds) {
            FileMetadata meta = found.get(fileId);
            if (meta == null) {
                // 与「非本人上传」同码同文案：不把 fileId 是否存在变成可探测信息（设计 §5.3 越权收敛口径）
                throw new AppException(ErrorCode.BAD_REQUEST, "附件不存在，请先调用 /files/upload");
            }
            if (currentUserId != null && meta.getCreatedBy() != null
                    && !currentUserId.equals(meta.getCreatedBy())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "附件不存在，请先调用 /files/upload");
            }
        }
    }

    /**
     * 按对象键查元数据（公开对象访问用）。
     *
     * <p>对象键含随机 UUID，属不可猜的能力型路径；查不到时返回 null 由调用方转 404，
     * 不抛业务异常，避免把「键是否存在」暴露成可探测的差异化响应。
     */
    public FileMetadata findByObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return fileMetadataMapper.selectOne(
                new LambdaQueryWrapper<FileMetadata>().eq(FileMetadata::getObjectKey, objectKey));
    }

    public Map<String, Object> toView(FileMetadata meta) {
        return Map.of(
                "id", meta.getId(),
                "fileId", meta.getId(),
                "fileName", meta.getFileName(),
                "contentType", meta.getContentType() == null ? "" : meta.getContentType(),
                "size", meta.getSize() == null ? 0L : meta.getSize(),
                "bizType", meta.getBizType() == null ? "" : meta.getBizType(),
                "url", objectStorageClient.resolveUrl(meta.getBucket(), meta.getObjectKey()));
    }

    /**
     * 按 fileId 批量取视图（附件回显：{@code url} / {@code fileName}）。
     *
     * <p>不抛异常：附件可能指向一条已被清理的 file_metadata（孤儿文件问题本期不做清理），
     * 回显时**跳过该条**（返回的 map 里没有它），但不能因为一条坏引用让整个 record-sheet 读不出来。
     *
     * <p><b>调用方不得因此丢弃这一行</b>：查不到的引用必须原样保留在返回结果里
     * （{@code fileName} / {@code url} 为 null，前端按 null 兜底展示）。读的时候丢行，
     * 保存时该 {@code fileId} 就不在请求体里，聚合写会把它**软删** ——
     * 一个纯展示层的取舍会变成静默数据丢失（设计 §4.3）。
     */
    public Map<Long, Map<String, Object>> viewsByIds(Collection<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        return fileMetadataMapper.selectBatchIds(fileIds).stream()
                .collect(Collectors.toMap(FileMetadata::getId, this::toView));
    }

    public InputStream openStream(FileMetadata meta) {
        return objectStorageClient.get(meta.getBucket(), meta.getObjectKey());
    }

    /** 将内存字节写入对象存储并登记元数据（合同 Word 导出等）。 */
    @Transactional
    public FileMetadata storeBytes(byte[] data, String fileName, String contentType, String bizType) {
        if (data == null || data.length == 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "文件内容为空");
        }
        String original = fileName == null || fileName.isBlank() ? "file.bin" : fileName;
        String objectKey = LocalDate.now() + "/" + UUID.randomUUID().toString().replace("-", "")
                + "_" + sanitize(original);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data);
            try (InputStream in = new java.io.ByteArrayInputStream(data)) {
                ObjectStorageClient.StoredObject stored = objectStorageClient.put(
                        objectKey, in, data.length, contentType);
                FileMetadata meta = new FileMetadata();
                meta.setBucket(stored.bucket());
                meta.setObjectKey(stored.objectKey());
                meta.setFileName(original);
                meta.setContentType(contentType);
                meta.setHashSha256(HexFormat.of().formatHex(digest.digest()));
                meta.setSize(stored.size());
                meta.setBizType(bizType == null || bizType.isBlank() ? "general" : bizType);
                meta.setCreatedBy(SecurityUtils.currentUserIdOrNull());
                meta.setCreatedAt(LocalDateTime.now());
                fileMetadataMapper.insert(meta);
                return meta;
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "文件保存失败: " + e.getMessage());
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").getBytes(StandardCharsets.UTF_8).length > 180
                ? name.substring(Math.max(0, name.length() - 80))
                : name;
    }
}
