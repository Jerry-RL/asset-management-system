package com.ams.platform.storage;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.config.AmsProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

/**
 * 本地文件系统对象存储（默认开发态，兼容 file_metadata 元数据模型）。
 */
@Component
public class LocalObjectStorageClient implements ObjectStorageClient {

    private final AmsProperties.Storage props;
    private final Path root;

    public LocalObjectStorageClient(AmsProperties amsProperties) {
        this.props = amsProperties.getStorage();
        this.root = Path.of(props.getLocalDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地上传目录: " + root, e);
        }
    }

    @Override
    public StoredObject put(String objectKey, InputStream data, long size, String contentType) {
        try {
            Path target = root.resolve(objectKey).normalize();
            if (!target.startsWith(root)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "非法对象键");
            }
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
            String bucket = props.getBucket() == null || props.getBucket().isBlank() ? "ams" : props.getBucket();
            return new StoredObject(bucket, objectKey, size >= 0 ? size : Files.size(target));
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "文件存储失败: " + e.getMessage());
        }
    }

    @Override
    public InputStream get(String bucket, String objectKey) {
        try {
            Path target = root.resolve(objectKey).normalize();
            if (!target.startsWith(root) || !Files.exists(target)) {
                throw new AppException(ErrorCode.NOT_FOUND, "文件不存在");
            }
            return Files.newInputStream(target);
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "文件读取失败: " + e.getMessage());
        }
    }

    @Override
    public String resolveUrl(String bucket, String objectKey) {
        if (props.getPublicBaseUrl() != null && !props.getPublicBaseUrl().isBlank()) {
            return props.getPublicBaseUrl().replaceAll("/$", "") + "/" + objectKey;
        }
        return "/api/v1/files/object/" + objectKey;
    }
}
