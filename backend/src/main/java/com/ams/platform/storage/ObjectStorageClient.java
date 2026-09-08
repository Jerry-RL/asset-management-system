package com.ams.platform.storage;

import java.io.InputStream;

/**
 * 对象存储抽象（ADR-0007）：本地目录或 S3/MinIO 兼容实现。
 */
public interface ObjectStorageClient {

    StoredObject put(String objectKey, InputStream data, long size, String contentType);

    InputStream get(String bucket, String objectKey);

    String resolveUrl(String bucket, String objectKey);

    record StoredObject(String bucket, String objectKey, long size) {
    }
}
