package com.audit.controller;

import com.audit.service.AuditService;
import com.audit.util.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上传代码文件并发起一次审计。
 *
 * <p>【这是干什么的】用户在前端选一个 .java / .py 文件，浏览器把这个文件以 multipart 形式
 * POST 过来；本类负责：判语言 → 落盘 → 建任务 → 跑审计 → 返回任务 id。
 *
 * <p>【输入 → 输出】
 * <pre>
 * POST /api/upload   (表单字段名必须是 file)
 *   ──▶ {"taskId":3,"fileName":"BadDemo.java","language":"java","savedAs":"1762xxx_BadDemo.java"}
 * </pre>
 *
 * <p>【失败边界】
 * <ul>
 *   <li>扩展名不是 .java/.py → 400，body 里带中文原因；</li>
 *   <li>文件为空 → 400；</li>
 *   <li>磁盘写失败 → 500（由 GlobalExceptionHandler 兜底）。</li>
 * </ul>
 *
 * <p>注意：现在是**同步**跑完整份文件才返回，小文件（几十到几百行）够用；
 * 大文件前端会等很久，改成"立即返回 taskId + 前端轮询"只需把 runAudit 挪到线程池。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UploadController {

    private final AuditService auditService;

    /** 上传文件落盘目录，相对工程根；yaml 里可覆盖。 */
    @Value("${audit.upload-dir:uploads}")
    private String uploadDir;

    /**
     * 收文件并跑一次审计。
     *
     * <p>同时挂两个路径：{@code /api/upload}（文档与前端用这个）和 {@code /api/audit}
     * （施工图验收命令里的写法），避免两处文档打架。
     */
    @PostMapping({"/upload", "/audit"})
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空，请重新选择");
        }
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String language = FileUtil.detectLanguage(fileName);

        Path saved;
        try {
            saved = FileUtil.save(Path.of(uploadDir), fileName, file.getBytes());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("文件保存失败：" + e.getMessage(), e);
        }

        Long taskId = auditService.createTask(fileName, language);
        auditService.runAudit(taskId, saved.toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("fileName", fileName);
        body.put("language", language);
        body.put("savedAs", saved.getFileName().toString());
        body.put("detail", "/api/audit/tasks/" + taskId + " 看进度，/api/audit/tasks/" + taskId + "/findings 看结果");
        log.info("上传完成：{} → 任务 {}（{}）", fileName, taskId, language);
        return body;
    }

    /** 只支持 .java / .py，其余给 400 而不是 500。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }
}
