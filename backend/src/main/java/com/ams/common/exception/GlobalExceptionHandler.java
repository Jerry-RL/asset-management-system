package com.ams.common.exception;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.platform.observability.service.AppLogRecorder;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理，统一输出 { code, message, data, traceId }。
 *
 * <p><strong>哪些异常会进 app_log</strong>（设计 D9）：只有真正落到
 * {@link #handleOther(Exception)} 的未捕获异常（5xx）会被 {@link AppLogRecorder} 记入
 * {@code app_log}。业务异常（{@link AppException}）、参数校验失败、重复键、请求体不可解析
 * 都是<strong>预期内的客户端问题</strong>，记进去只会淹没真正的故障信号，让 ERROR 告警失真。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AppLogRecorder appLogRecorder;

    public GlobalExceptionHandler(AppLogRecorder appLogRecorder) {
        this.appLogRecorder = appLogRecorder;
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleApp(AppException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage(), TraceIdUtil.get()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, detail, TraceIdUtil.get()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateKeyException ex) {
        log.warn("duplicate key: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.CONFLICT, "数据已存在，请勿重复提交", TraceIdUtil.get()));
    }

    /**
     * 请求体无法解析（畸形 JSON、类型不匹配）→ <strong>400</strong>。
     *
     * <p>这个处理器是必需的，不是锦上添花。没有它时畸形 JSON 会落到
     * {@link #handleOther(Exception)}：返回 500，并且在 app_log 里记一行
     * {@code app_type=backend} 的日志、还可能触发告警。于是一个匿名请求者只要循环发送畸形
     * JSON，就能无限灌库并把 ERROR 告警刷爆 —— 而这类请求体积很小、频率也在限流额度内，
     * 前端限流挡不住（它拦的是「谁来」，不是「内容对不对」）。
     *
     * <p>这改变了全仓既有行为：所有接口的畸形请求体从 500 变为 400。这才是正确的 HTTP 语义
     * —— 客户端发错了，不该由服务端以「内部错误」来报告。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        // 只 warn 不进 app_log：客户端畸形输入是可预期的，不是服务端故障
        log.warn("unreadable request body: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, TraceIdUtil.get()));
    }

    /**
     * 路径变量/查询参数类型不匹配 → <strong>400</strong>。
     *
     * <p>与 {@link #handleUnreadable} 同一类问题、同一个放大面：没有这个处理器时，
     * 一个<strong>免登录</strong>接口只要路径变量类型对不上就会落到
     * {@link #handleOther(Exception)}，返回 500 并在 app_log 里留下一条
     * {@code app_type=backend} 的假「后端故障」。
     *
     * <p>现成的例子就在仓里：{@code PublicAssetController} 是匿名白名单端点，且带
     * {@code @PathVariable Long assetId} —— 请求 {@code /api/v1/public/assets/abc/scan}
     * 就能不登录地持续制造「后端异常」并刷爆告警。这不是理论风险。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("argument type mismatch: {}={}", ex.getName(), ex.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        ErrorCode.BAD_REQUEST, "参数 " + ex.getName() + " 格式不正确", TraceIdUtil.get()));
    }

    /**
     * 未捕获异常 → 500，并<strong>同时写入 app_log</strong>。
     *
     * <p>这是「前端报错 ↔ 后端异常」共用 trace_id 的落点：端侧上报的 API 失败带着本次请求的
     * traceId，后端这里也写同一个 traceId，于是查询页能用一个 traceId 把两行串起来。
     *
     * <p>{@code appLogRecorder} 内部吞掉全部异常：<strong>日志系统故障绝不允许把业务响应
     * 变成另一个错误</strong>。这里也不加 {@code REQUIRES_NEW}（见 AppLogRecorder 的说明）。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        log.error("unhandled exception", ex);
        appLogRecorder.recordBackendException(ex, TraceIdUtil.get());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, TraceIdUtil.get()));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
