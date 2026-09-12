package com.ams.modules.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.system.entity.FileMetadata;
import com.ams.modules.system.mapper.FileMetadataMapper;
import com.ams.platform.security.LoginUser;
import com.ams.platform.storage.ObjectStorageClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 附件归属校验（设计 §4.3）。
 *
 * <p>{@code file_metadata} 只有 {@code created_by}（上传者）、没有公司列，因此归属口径就是
 * 「谁上传谁能挂」。两条失败路径必须同码同文案，避免把 fileId 的存在性与归属变成可探测信息。
 */
class FileServiceAttachGuardTest {

    private static final long USER_ID = 5L;

    private final FileMetadataMapper fileMetadataMapper = mock(FileMetadataMapper.class);
    private final ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
    private final FileService fileService = new FileService(fileMetadataMapper, objectStorageClient);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("附件归属：本人上传的文件可以引用")
    void ownUploadIsAccepted() {
        loginAs(USER_ID);
        when(fileMetadataMapper.selectBatchIds(any())).thenReturn(List.of(fileMeta(101L, USER_ID)));

        assertThatCode(() -> fileService.assertAttachable(List.of(101L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("附件归属：他人上传的文件被拒绝（400）")
    void otherUsersUploadIsRejected() {
        loginAs(USER_ID);
        when(fileMetadataMapper.selectBatchIds(any())).thenReturn(List.of(fileMeta(101L, 6L)));

        AppException rejected = rejection(() -> fileService.assertAttachable(List.of(101L)));

        assertThat(rejected.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("附件归属：没有上传者的历史 / 种子文件可以引用")
    void seededFileWithoutUploaderIsAccepted() {
        loginAs(USER_ID);
        when(fileMetadataMapper.selectBatchIds(any())).thenReturn(List.of(fileMeta(101L, null)));

        assertThatCode(() -> fileService.assertAttachable(List.of(101L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("附件归属：fileId 不存在与「非本人上传」同码同文案")
    void missingFileIdIsRejected() {
        loginAs(USER_ID);

        when(fileMetadataMapper.selectBatchIds(any())).thenReturn(List.of(fileMeta(101L, 6L)));
        AppException foreign = rejection(() -> fileService.assertAttachable(List.of(101L)));

        when(fileMetadataMapper.selectBatchIds(any())).thenReturn(List.of());
        AppException missing = rejection(() -> fileService.assertAttachable(List.of(101L)));

        assertThat(missing.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(foreign.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        // 完整文案相等而非子串：本仓库出现过「两条错误分支共用消息子串」导致的假绿用例
        assertThat(missing.getMessage()).isEqualTo(foreign.getMessage());
    }

    // ---- 辅助 ----

    private static void loginAs(long userId) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static AppException rejection(Runnable action) {
        try {
            action.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("预期抛出 AppException，但调用正常返回");
    }

    private static FileMetadata fileMeta(long id, Long createdBy) {
        FileMetadata meta = new FileMetadata();
        meta.setId(id);
        meta.setCreatedBy(createdBy);
        return meta;
    }
}
