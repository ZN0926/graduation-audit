package com.audit.service;

import com.audit.model.AuditTask;
import com.audit.model.Finding;
import com.audit.model.Report;
import com.audit.model.Rule;
import com.audit.repository.AuditTaskRepository;
import com.audit.repository.FindingRepository;
import com.audit.repository.ReportRepository;
import com.audit.repository.RuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 报告生成：把一个任务的 finding 汇总成合规率 + Markdown + HTML。
 *
 * <p>【功能】把散落的 finding 记录整理成一份人能看懂、能贴进论文的审计报告。
 *
 * <p>【作用】这是整条流水线的最后一环，也是答辩时唯一被完整看到的产物：
 * 前面的切片、规则、提示词、校验，全部收敛到"这份文件有 N 处问题、严重程度如何、
 * 每一处怎么改"。
 *
 * <p>【输入】一个跑完的 {@code taskId}（对应的 audit_task 状态通常是 DONE）。
 *
 * <p>【输出】{@link Report} 实体：合规率 {@code complianceRate}、一句话结论
 * {@code summaryText}、Markdown 正文、HTML 正文；同一个 taskId 重复生成会覆盖旧报告。
 *
 * <p>【怎么用】
 * <ol>
 *   <li>审计跑完后调用 {@link #generate(Long)}，或直接访问
 *       {@code GET /api/report/{taskId}}（接口层会自动调用它）；</li>
 *   <li>前端拿 {@code html} 直接渲染、拿 {@code markdown} 供下载；</li>
 *   <li>想重新生成（比如改了规则库）再调一次即可，不会产生重复记录。</li>
 * </ol>
 *
 * <p>【例子】任务 1（BadDemo.java，196 条 Java 规则，命中 1 条 SQL 注入）：
 * <pre>
 * complianceRate = 1 - 1/196 ≈ 0.9949 → 报告里写"99.5%"
 * summaryText    = "本次审计共发现 1 处问题，涉及 1 条规则；其中高危 1 处。
 *                   规则库共 196 条，合规率 99.5%。最需要先修的是「SQL 语句拼接」"
 * </pre>
 *
 * <p>合规率的口径（论文里要写清楚）：**1 − 命中的规则去重数 / 该语言规则总数**。
 * 不使用"命中块数 / 总块数"，因为规则库才是有意义的分母——一条规则没被违反，
 * 才叫"这件事合规"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 严重级别的展示顺序：高危排前面，报告里一眼能看到重点。 */
    private static final List<String> SEVERITY_ORDER = List.of("HIGH", "MEDIUM", "LOW", "INFO");

    private final AuditTaskRepository taskRepository;
    private final FindingRepository findingRepository;
    private final RuleRepository ruleRepository;
    private final ReportRepository reportRepository;

    /**
     * 生成（或覆盖）某个任务的报告。
     *
     * @param taskId 审计任务 id
     * @return 已落库的报告实体
     * @throws IllegalArgumentException 任务不存在
     */
    @Transactional
    public Report generate(Long taskId) {
        AuditTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));

        List<Finding> findings = findingRepository.findByTaskIdAndViolated(taskId, true);
        findings.sort(Comparator.comparingInt(f -> f.getStartLine() == null ? 0 : f.getStartLine()));

        long ruleTotal = ruleRepository.countByLanguage(task.getLanguage());
        Set<String> hitRuleIds = findings.stream()
                .map(Finding::getRuleId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        double compliance = ruleTotal == 0
                ? 1.0
                : 1.0 - (double) hitRuleIds.size() / (double) ruleTotal;
        compliance = Math.max(0.0, Math.min(1.0, compliance));

        Map<String, Rule> ruleById = new LinkedHashMap<>();
        ruleRepository.findByLanguage(task.getLanguage())
                .forEach(r -> ruleById.put(r.getRuleId(), r));

        Map<String, Long> bySeverity = new TreeMap<>(Comparator.comparingInt(
                s -> SEVERITY_ORDER.indexOf(s) < 0 ? 99 : SEVERITY_ORDER.indexOf(s)));
        findings.forEach(f -> bySeverity.merge(norm(f.getSeverity()), 1L, Long::sum));

        Map<String, Long> byCategory = new TreeMap<>();
        findings.forEach(f -> {
            Rule r = ruleById.get(f.getRuleId());
            byCategory.merge(r == null || r.getCategory() == null ? "unknown" : r.getCategory(), 1L, Long::sum);
        });

        String summary = buildSummary(task, findings, bySeverity, hitRuleIds.size(), ruleTotal, compliance);
        String markdown = buildMarkdown(task, findings, ruleById, bySeverity, byCategory,
                hitRuleIds.size(), ruleTotal, compliance, summary);
        String html = buildHtml(task, summary, bySeverity, byCategory, compliance);

        Report report = reportRepository.findByTaskId(taskId).orElseGet(Report::new);
        report.setTaskId(taskId);
        report.setSummaryText(summary);
        report.setComplianceRate(compliance);
        report.setMarkdown(markdown);
        report.setHtml(html);
        report.setCreatedAt(java.time.LocalDateTime.now());
        Report saved = reportRepository.save(report);

        log.info("任务 {} 报告生成：违规 {} 条，命中规则 {} / {}，合规率 {:.1f}%",
                taskId, findings.size(), hitRuleIds.size(), ruleTotal, compliance * 100);
        return saved;
    }

    /** 取已有报告；没有就现场生成一份。 */
    @Transactional
    public Report getOrGenerate(Long taskId) {
        return reportRepository.findByTaskId(taskId).orElseGet(() -> generate(taskId));
    }

    // ---------------------------------------------------------------- 正文拼装

    private String buildSummary(AuditTask task, List<Finding> findings, Map<String, Long> bySeverity,
                                int hitRules, long ruleTotal, double compliance) {
        if (findings.isEmpty()) {
            return "本次审计未发现违规：文件 " + task.getFileName() + " 与规则库中 "
                    + ruleTotal + " 条 " + task.getLanguage() + " 规则全部一致，合规率 100%。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本次审计共发现 ").append(findings.size()).append(" 处问题，涉及 ")
                .append(hitRules).append(" 条规则");
        long high = bySeverity.getOrDefault("HIGH", 0L);
        if (high > 0) {
            sb.append("；其中高危 ").append(high).append(" 处");
        }
        sb.append("。规则库共 ").append(ruleTotal).append(" 条，合规率 ")
                .append(String.format(Locale.ROOT, "%.1f%%", compliance * 100)).append("。");

        Finding worst = findings.stream()
                .filter(f -> "HIGH".equalsIgnoreCase(f.getSeverity()))
                .findFirst()
                .orElse(findings.get(0));
        sb.append("最需要先修的是第 ").append(worst.getStartLine()).append(" 行（")
                .append(worst.getRuleId()).append("）。");
        return sb.toString();
    }

    private String buildMarkdown(AuditTask task, List<Finding> findings, Map<String, Rule> ruleById,
                                 Map<String, Long> bySeverity, Map<String, Long> byCategory,
                                 int hitRules, long ruleTotal, double compliance, String summary) {
        StringBuilder md = new StringBuilder();
        md.append("# 代码审计报告\n\n");
        md.append("> ").append(summary).append("\n\n");

        md.append("## 一、任务信息\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 文件 | ").append(esc(task.getFileName())).append(" |\n");
        md.append("| 语言 | ").append(esc(task.getLanguage())).append(" |\n");
        md.append("| 任务号 | ").append(task.getId()).append(" |\n");
        md.append("| 规则版本 | ").append(esc(task.getRuleVersion())).append(" |\n");
        md.append("| 代码块数 | ").append(task.getTotalChunks()).append(" |\n");
        md.append("| 状态 | ").append(esc(task.getStatus())).append(" |\n");
        md.append("| 完成时间 | ")
                .append(task.getFinishedAt() == null ? "—" : TIME_FMT.format(task.getFinishedAt()))
                .append(" |\n\n");

        md.append("## 二、总体评估\n\n");
        md.append("| 指标 | 值 |\n|---|---|\n");
        md.append("| 违规总数 | ").append(findings.size()).append(" |\n");
        md.append("| 命中规则数 | ").append(hitRules).append(" / ").append(ruleTotal).append(" |\n");
        md.append("| **合规率** | **").append(String.format(Locale.ROOT, "%.1f%%", compliance * 100))
                .append("** |\n");
        for (Map.Entry<String, Long> e : bySeverity.entrySet()) {
            md.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        md.append('\n');

        if (!byCategory.isEmpty()) {
            md.append("## 三、问题分布\n\n| 分类 | 条数 |\n|---|---|\n");
            byCategory.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
            md.append('\n');
        }

        md.append("## 四、违规明细\n\n");
        if (findings.isEmpty()) {
            md.append("无。\n");
        } else {
            int i = 1;
            for (Finding f : findings) {
                Rule rule = ruleById.get(f.getRuleId());
                md.append("### ").append(i++).append(". ").append(lineRange(f))
                        .append(' ').append(rule == null ? "" : esc(rule.getTitle())).append("\n\n");
                md.append("- **规则**：`").append(esc(f.getRuleId())).append("`")
                        .append(rule == null ? "" : "（" + esc(rule.getCategory()) + " / " + esc(rule.getCwe()) + "）")
                        .append("\n");
                md.append("- **等级**：").append(esc(f.getSeverity()))
                        .append("　**置信度**：").append(conf(f.getConfidence())).append("\n");
                if (f.getCodeSnippet() != null) {
                    md.append("\n```").append(task.getLanguage()).append("\n")
                            .append(f.getCodeSnippet()).append("\n```\n\n");
                }
                md.append("- **原因**：").append(esc(f.getReason())).append("\n");
                md.append("- **修复建议**：").append(esc(f.getFixSuggestion())).append("\n\n");
            }
        }

        md.append("---\n\n*本报告由「基于大语言模型的智能代码审计系统」自动生成。")
                .append("判定由大模型完成，结论仅供参考，高危项建议人工复核。*\n");
        return md.toString();
    }

    private String buildHtml(AuditTask task, String summary, Map<String, Long> bySeverity,
                             Map<String, Long> byCategory, double compliance) {
        StringBuilder tc = new StringBuilder();
        bySeverity.forEach((k, v) -> tc.append("<tr><td>").append(esc(k)).append("</td><td>")
                .append(v).append("</td></tr>"));
        StringBuilder cc = new StringBuilder();
        byCategory.forEach((k, v) -> cc.append("<tr><td>").append(esc(k)).append("</td><td>")
                .append(v).append("</td></tr>"));

        return """
                <!doctype html>
                <html lang="zh-CN"><head><meta charset="utf-8">
                <title>代码审计报告 · %s</title>
                <style>
                body{font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;
                     max-width:900px;margin:32px auto;padding:0 16px;color:#222;line-height:1.7}
                h1{font-size:24px}h2{font-size:18px;margin-top:28px;border-left:4px solid #2f6fed;padding-left:8px}
                table{border-collapse:collapse;width:100%%;margin:8px 0}
                th,td{border:1px solid #e3e6eb;padding:6px 10px;text-align:left;font-size:14px}
                th{background:#f7f8fa}
                .summary{background:#f3f7ff;border:1px solid #d6e4ff;padding:12px 14px;border-radius:6px}
                </style></head><body>
                <h1>代码审计报告</h1>
                <div class="summary">%s</div>
                <h2>任务信息</h2>
                <table>
                <tr><th>文件</th><td>%s</td></tr>
                <tr><th>语言</th><td>%s</td></tr>
                <tr><th>任务号</th><td>%d</td></tr>
                <tr><th>规则版本</th><td>%s</td></tr>
                <tr><th>代码块数</th><td>%d</td></tr>
                <tr><th>合规率</th><td><b>%s</b></td></tr>
                </table>
                <h2>严重级别分布</h2>
                <table><tr><th>级别</th><th>条数</th></tr>%s</table>
                <h2>问题分类分布</h2>
                <table><tr><th>分类</th><th>条数</th></tr>%s</table>
                <p style="color:#888;font-size:13px">违规明细见 Markdown 版；本页用于快速浏览概览。</p>
                </body></html>
                """.formatted(esc(task.getFileName()), esc(summary), esc(task.getFileName()),
                esc(task.getLanguage()), task.getId(), esc(task.getRuleVersion()),
                task.getTotalChunks(), String.format(Locale.ROOT, "%.1f%%", compliance * 100),
                tc, cc);
    }

    // ---------------------------------------------------------------- 小工具

    private static String lineRange(Finding f) {
        if (f.getStartLine() == null) {
            return "(行号未知)";
        }
        return f.getEndLine() == null || f.getEndLine().equals(f.getStartLine())
                ? "第 " + f.getStartLine() + " 行"
                : "第 " + f.getStartLine() + "–" + f.getEndLine() + " 行";
    }

    private static String conf(Double c) {
        return c == null ? "—" : String.format(Locale.ROOT, "%.2f", c);
    }

    private static String norm(String severity) {
        return severity == null ? "MEDIUM" : severity.trim().toUpperCase(Locale.ROOT);
    }

    /** HTML/Markdown 里最容易被代码片段带跑的两个字符。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
