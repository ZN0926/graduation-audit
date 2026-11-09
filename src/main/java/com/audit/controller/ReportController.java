package com.audit.controller;

import com.audit.model.Report;
import com.audit.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报告的查询与导出接口。
 *
 * <p>【功能】把 {@link ReportService} 生成的报告以 JSON / Markdown / HTML 三种形式递给前端或浏览器。
 *
 * <p>【作用】前端报告页的第一屏要 JSON（合规率 + 结论 + 明细），下载按钮要 Markdown，
 * 答辩现场想直接开个网页给人看要 HTML——三种格式，三次请求，互相不干扰。
 *
 * <p>【输入 → 输出】
 * <pre>
 * GET /api/report/{taskId}            ──▶ JSON：{taskId, complianceRate, summaryText, markdown, html, findings?}
 * GET /api/report/{taskId}/markdown   ──▶ text/markdown（浏览器会直接下载或显示原文）
 * GET /api/report/{taskId}/html       ──▶ text/html（直接可视化）
 * POST /api/report/{taskId}/regenerate──▶ 强制重算并返回 JSON
 * </pre>
 *
 * <p>【怎么用】
 * <ol>
 *   <li>审计跑完，打开 {@code /api/report/1/markdown} 就能看到完整报告；</li>
 *   <li>前端代码：{@code axios.get('/api/report/' + taskId).then(r => this.report = r.data)}；</li>
 *   <li>改了规则库想重算，POST 一次 {@code /regenerate}。</li>
 * </ol>
 *
 * <p>【例子】{@code GET http://localhost:8080/api/report/1/html} 在浏览器里就是一张
 * "代码审计报告"网页：任务信息表 + 合规率 99.5% + 严重级别分布；同一任务的
 * {@code /markdown} 则给出带代码片段的完整明细。
 *
 * <p>【失败边界】taskId 不存在 → 400；任务还在 RUNNING 时生成会把当前已有 finding 汇总
 * （报告是"快照"，不阻塞审计）。
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /** 报告 JSON：不存在就现场生成。 */
    @GetMapping("/{taskId}")
    public Map<String, Object> report(@PathVariable Long taskId) {
        Report r = reportService.getOrGenerate(taskId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", r.getTaskId());
        body.put("complianceRate", r.getComplianceRate());
        body.put("summaryText", r.getSummaryText());
        body.put("markdown", r.getMarkdown());
        body.put("html", r.getHtml());
        body.put("createdAt", r.getCreatedAt());
        return body;
    }

    /** 只取 Markdown 正文，用于"下载报告"。 */
    @GetMapping(value = "/{taskId}/markdown", produces = "text/markdown;charset=UTF-8")
    public String markdown(@PathVariable Long taskId) {
        return reportService.getOrGenerate(taskId).getMarkdown();
    }

    /** 只取 HTML，浏览器直接打开。 */
    @GetMapping(value = "/{taskId}/html", produces = MediaType.TEXT_HTML_VALUE)
    public String html(@PathVariable Long taskId) {
        return reportService.getOrGenerate(taskId).getHtml();
    }

    /** 强制重算（改了规则库或想换措辞时用）。 */
    @PostMapping("/{taskId}/regenerate")
    public Map<String, Object> regenerate(@PathVariable Long taskId) {
        Report r = reportService.generate(taskId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", r.getTaskId());
        body.put("complianceRate", r.getComplianceRate());
        body.put("summaryText", r.getSummaryText());
        body.put("regenerated", true);
        return body;
    }
}
