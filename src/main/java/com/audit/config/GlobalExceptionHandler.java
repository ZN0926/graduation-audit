package com.audit.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：让前端拿到的永远是 JSON，而不是 Whitelabel 错误页。
 *
 * <p>【功能】把 controller/service 里抛出的异常，统一翻译成
 * {@code {timestamp,status,error,path}} 四字段的 JSON 响应。
 *
 * <p>【作用】没有它，任何未处理异常都会返回 Spring 的 HTML 错误页
 * （{@code Whitelabel Error Page} + "There was an unexpected error"），前端
 * {@code JSON.parse} 直接报错，排查时还得去翻 IDE 日志。有了它，前端只要看
 * {@code response.data.error} 就知道出了什么事。
 *
 * <p>【输入】任意 controller 抛出的异常（不用手工调用）。
 *
 * <p>【输出】HTTP 状态码 + JSON。映射规则：
 * <table border="1">
 *   <tr><th>异常</th><th>状态码</th><th>典型场景</th></tr>
 *   <tr><td>{@link IllegalArgumentException}</td><td>400</td><td>上传了 .txt、taskId 不存在</td></tr>
 *   <tr><td>{@link IllegalStateException}</td><td>500</td><td>规则库为空、文件写不进去</td></tr>
 *   <tr><td>{@link MaxUploadSizeExceededException}</td><td>413</td><td>文件超过 10MB</td></tr>
 *   <tr><td>{@link Exception}</td><td>500</td><td>其它兜底</td></tr>
 * </table>
 *
 * <p>【怎么用】类上加 {@code @RestControllerAdvice} 即生效，方法里什么都不用写。
 *
 * <p>【例子】
 * <pre>
 * POST /api/upload  带着 note.txt
 * → 400 {"timestamp":"2026-11-09T13:20:01","status":400,
 *        "error":"只支持 .java 和 .py 文件，收到：note.txt"}
 * </pre>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 请求参数/文件类型不对：用户能自己修，给 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 服务端状态不对（规则库为空、磁盘写失败）：给 500，但保留中文原因。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException e) {
        log.warn("业务状态异常：{}", e.getMessage());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    /** 上传超过 multipart 限制（yaml 里 10MB）。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过大小限制（10MB）");
    }

    /** 兜底：日志留完整堆栈，响应只给一句人话，不泄露内部类名。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("未处理的异常", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR,
                "服务器内部错误：" + e.getClass().getSimpleName());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", LocalDateTime.now().toString());
        m.put("status", status.value());
        m.put("error", message == null ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status).body(m);
    }
}
