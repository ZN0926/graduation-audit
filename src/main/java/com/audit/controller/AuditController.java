package com.audit.controller;

import com.audit.model.AuditTask;
import com.audit.model.Finding;
import com.audit.repository.AuditTaskRepository;
import com.audit.repository.FindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计任务的查询接口：列表、单个任务进度、某任务的违规明细。
 *
 * <p>【这组接口是干什么的】上传是"提交作业"，这里三个接口是"查作业"：
 * 前端传完文件拿到 taskId，然后轮询 {@code /tasks/{id}} 看进度，
 * 跑完后调 {@code /tasks/{id}/findings} 拿每条违规去渲染报告页。
 *
 * <p>【输入 → 输出】
 * <pre>
 * GET /api/audit/tasks                      ──▶ [{id,fileName,language,status,totalChunks,doneChunks,...}]
 * GET /api/audit/tasks/3                    ──▶ 单个任务 + violations 计数
 * GET /api/audit/tasks/3/findings           ──▶ 该任务全部违规（按起始行号升序）
 * </pre>
 *
 * <p>【失败边界】taskId 不存在 → 400（由 IllegalArgumentException 统一转）。
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditTaskRepository taskRepository;
    private final FindingRepository findingRepository;

    /** 任务列表，最新的在前（前端任务列表页用）。 */
    @GetMapping("/tasks")
    public List<AuditTask> tasks() {
        return taskRepository.findAllByOrderByIdDesc();
    }

    /** 单个任务：状态 + 进度 + 违规条数，轮询这个接口。 */
    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable Long id) {
        AuditTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + id));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task", task);
        body.put("violations", findingRepository.countByTaskIdAndViolated(id, true));
        body.put("checked", findingRepository.findByTaskId(id).size());
        return body;
    }

    /** 某任务的全部违规明细，按起始行号升序（报告页按源码顺序展示）。 */
    @GetMapping("/tasks/{id}/findings")
    public List<Finding> findings(@PathVariable Long id) {
        List<Finding> list = findingRepository.findByTaskIdAndViolated(id, true);
        list.sort((a, b) -> Integer.compare(
                a.getStartLine() == null ? 0 : a.getStartLine(),
                b.getStartLine() == null ? 0 : b.getStartLine()));
        return list;
    }
}
