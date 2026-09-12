package com.ams.modules.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.ams.modules.record.dto.AttachmentRef;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.dto.ReceiveInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 聚合 DTO 的序列化契约（设计 §5.2 的请求体形状）。
 *
 * <p>用真实的 {@link ObjectMapper} 而不是断言 getter：这份形状是前端 `lib/recordSheet.ts`
 * 逐字对齐的接口，字段名拼错或日期格式改变都会让前端静默拿到 undefined。
 *
 * <p><strong>必须关掉 {@code WRITE_DATES_AS_TIMESTAMPS}</strong>：Spring Boot 默认就把日期序列化成
 * ISO-8601 字符串（{@code "2026-08-01"}），而裸的 {@code JavaTimeModule} 默认输出
 * {@code [2026,8,1]} 数组。若这里用裸配置，测试会锁死一个**生产不会出现**的格式，
 * 前端照着数组写解析、上线即报错。
 */
class RecordSheetDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("读视图默认集合非 null，前端可以直接 map 而不必判空")
    void viewCollectionsAreNeverNull() {
        RecordSheetView view = new RecordSheetView();
        assertThat(view.getReceives()).isNotNull().isEmpty();
        assertThat(view.getDisposalRecords()).isNotNull().isEmpty();
        assertThat(view.getDisposals()).isNotNull().isEmpty();
        assertThat(view.getSourceInfo()).isNull();
    }

    @Test
    @DisplayName("请求体默认集合非 null，服务层不必对 null 做分支")
    void requestCollectionsAreNeverNull() {
        RecordSheetRequest request = new RecordSheetRequest();
        assertThat(request.getReceives()).isNotNull().isEmpty();
        assertThat(request.getDisposalRecords()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("序列化字段名与前端约定一致，日期为 yyyy-MM-dd")
    void serializesToAgreedShape() throws Exception {
        ReceiveInput input = new ReceiveInput();
        input.setId(12L);
        input.setHandoverType("receive");
        input.setHandoverUserName("张三");
        input.setHandoverDate(LocalDate.of(2026, 8, 1));
        AttachmentRef ref = new AttachmentRef();
        ref.setFileId(101L);
        ref.setSort(0);
        input.getAttachments().add(ref);

        String json = objectMapper.writeValueAsString(input);

        assertThat(json).contains("\"handoverType\":\"receive\"");
        assertThat(json).contains("\"handoverUserName\":\"张三\"");
        assertThat(json).contains("\"handoverDate\":\"2026-08-01\"");
        assertThat(json).contains("\"fileId\":101");
        assertThat(json).doesNotContain("handover_date");
    }

    @Test
    @DisplayName("反序列化容忍缺省字段：只传要改的字段即可")
    void deserializesPartialPayload() throws Exception {
        RecordSheetRequest request = objectMapper.readValue(
                "{\"receives\":[{\"handoverUserName\":\"李四\"}]}", RecordSheetRequest.class);

        assertThat(request.getReceives()).hasSize(1);
        ReceiveInput first = request.getReceives().get(0);
        assertThat(first.getHandoverUserName()).isEqualTo("李四");
        assertThat(first.getId()).isNull();
        assertThat(first.getAttachments()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("列表字段缺失时反序列化为空列表而不是 null")
    void missingListsBecomeEmpty() throws Exception {
        ReceiveInput input = objectMapper.readValue("{}", ReceiveInput.class);
        assertThat(input.getIssues()).isNotNull().isEmpty();
        assertThat(input.getAttachments()).isNotNull().isEmpty();
        assertThat(input.getIssues()).isInstanceOf(List.class);
    }
}
