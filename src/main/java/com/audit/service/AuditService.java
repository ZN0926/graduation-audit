package com.audit.service;

import com.audit.model.AuditTask;
import com.audit.model.CodeChunk;
import com.audit.model.Finding;
import com.audit.model.Rule;
import com.audit.prompt.PromptTemplates;
import com.audit.repository.AuditTaskRepository;
import com.audit.repository.FindingRepository;
import com.audit.repository.RuleRepository;
import com.audit.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 审计编排：把一个代码文件跑成一串 finding。
 *
 * <p>流程（这就是论文第 4 章那张流水线图）：
 * <pre>
 * 文件 ──CodeParseService──▶ 代码块（带行号、带的线索 categoryHints）
 *                                  │
 *              ┌───────────────────┴────────────────────┐
 *              ▼                                        ▼
 *   按线索挑规则（只带相关类别，上限 15 条）      逐块调模型（temperature=0）
 *              │                                        │
 *              └──────────────▶ 两道落地校验 ◀──────────┘
 *                        ① ruleId 必须在本次清单内（防编造规则）
 *                        ② 行号必须落在本块区间内（防错位）
 *                                  ▼
 *                          finding 表（只存违规项）
 * </pre>
 *
 * <p>为什么只把"候选规则"喂给模型、而不是整库：一门语言几百条规则，全塞进提示词会
 * ①超出上下文 ②模型注意力被无关条目稀释、乱判。先按代码里出现的关键词粗筛，
 * 命中率不降，token 降一半以上。
 *
 * <p>为什么只存 violated=true：合规项没有信息量，却会让 finding 表膨胀几十倍；
 * 合规率用"违规规则去重数 / 规则总数"算得出来（见块 6）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /** 单块最多带多少条规则给模型：多了 token 爆炸且判定质量下降。 */
    private static final int MAX_RULES_PER_CHUNK = 15;

    /** user 提示词里代码与规则的长度上限，防止单块异常大。 */
    private static final int MAX_RULE_HINT_CHARS = 300;

    /** 代码切片线索 → 规则库分类。线索只有 6 种，规则分类有 11 种，必须翻译一次。 */
    private static final Map<String, List<String>> HINT_TO_CATEGORIES = Map.of(
            "sql", List.of("injection"),
            "exec", List.of("injection", "deserialization"),
            "crypto", List.of("crypto"),
            "path", List.of("path-traversal"),
            "log", List.of("logging", "secrets"),
            "auth", List.of("auth", "secrets"));

    private final CodeParseService codeParseService;
    private final LlmService llmService;
    private final RuleRepository ruleRepository;
    private final AuditTaskRepository taskRepository;
    private final FindingRepository findingRepository;

    /** 建一个待跑的任务，返回 id（上传接口先建任务，再异步/同步跑）。 */
    @Transactional
    public Long createTask(String fileName, String language) {
        AuditTask task = new AuditTask();
        task.setFileName(fileName);
        task.setLanguage(language);
        task.setStatus("PENDING");
        task.setTotalChunks(0);
        task.setDoneChunks(0);
        return taskRepository.save(task).getId();
    }

    /**
     * 跑完一个任务（同步）。异常不外抛，统一落到 {@code status=FAILED + errorMsg}，
     * 这样接口永远能返回一个可查询的任务，而不是 500。
     */
    public void runAudit(Long taskId, String filePath) {
        AuditTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
        int droppedRuleId = 0;
        int droppedLine = 0;

        try {
            task.setStatus("RUNNING");
            taskRepository.save(task);

            List<Rule> rules = ruleRepository.findByLanguage(task.getLanguage());
            if (rules.isEmpty()) {
                throw new IllegalStateException("规则库为空（language=" + task.getLanguage()
                        + "），先执行块 4 的规则导入或蒸馏");
            }
            task.setRuleVersion("rules-" + rules.size());

            List<CodeChunk> chunks = codeParseService.parse(filePath, task.getLanguage());
            task.setTotalChunks(chunks.size());
            taskRepository.save(task);
            log.info("任务 {} 开始：文件 {}，语言 {}，{} 块，规则库 {} 条",
                    taskId, task.getFileName(), task.getLanguage(), chunks.size(), rules.size());

            for (CodeChunk chunk : chunks) {
                List<Rule> picked = pickRules(rules, chunk);
                if (!picked.isEmpty()) {
                    int[] dropped = judge(task, filePath, chunk, picked);
                    droppedRuleId += dropped[0];
                    droppedLine += dropped[1];
                }
                task.setDoneChunks(task.getDoneChunks() + 1);
                taskRepository.save(task);
            }

            task.setStatus("DONE");
            task.setFinishedAt(LocalDateTime.now());
            log.info("任务 {} 完成：{} 块，违规 {} 条，丢弃（规则不在清单）{}，丢弃（行号越界）{}",
                    taskId, chunks.size(),
                    findingRepository.countByTaskIdAndViolated(taskId, true), droppedRuleId, droppedLine);
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setErrorMsg(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.error("任务 {} 失败", taskId, e);
        }
        taskRepository.save(task);
    }

    /**
     * 判定一个代码块。
     *
     * @return {@code [规则外引用数, 行号越界数]}，用来在日志里量化模型的幻觉程度
     */
    private int[] judge(AuditTask task, String filePath, CodeChunk chunk, List<Rule> rules) {
        String rulesJson = JsonUtil.MAPPER.writeValueAsString(
                rules.stream().map(this::brief).toList());

        String raw;
        try {
            raw = llmService.chatForObject(PromptTemplates.AUDIT_SYSTEM,
                    PromptTemplates.buildAuditUser(task.getLanguage(), task.getFileName(),
                            chunk.getStartLine(), chunk.getEndLine(), chunk.getCode(), rulesJson));
        } catch (RuntimeException e) {
            // 单块失败不该拖死整个任务：记日志，继续下一块
            log.warn("第 {}-{} 行判定调用失败：{}", chunk.getStartLine(), chunk.getEndLine(), e.getMessage());
            return new int[]{0, 0};
        }

        JsonNode root;
        try {
            root = JsonUtil.MAPPER.readTree(raw);
        } catch (RuntimeException e) {
            log.warn("第 {}-{} 行返回不是合法 JSON：{}", chunk.getStartLine(), chunk.getEndLine(),
                    abbreviate(raw));
            return new int[]{0, 0};
        }
        JsonNode violations = root == null ? null : root.path("violations");
        if (violations == null || !violations.isArray()) {
            return new int[]{0, 0};
        }

        Set<String> allowed = new LinkedHashSet<>(rules.stream().map(Rule::getRuleId).toList());
        Map<String, Rule> byId = new LinkedHashMap<>();
        rules.forEach(r -> byId.put(r.getRuleId(), r));

        int badRuleId = 0;
        int badLine = 0;
        for (JsonNode v : violations) {
            if (!v.path("violated").asBoolean(false)) {
                continue;
            }
            String ruleId = v.path("ruleId").asString("").trim();
            if (!allowed.contains(ruleId)) {
                badRuleId++;
                log.info("丢弃：规则 {} 不在本次清单内", ruleId);
                continue;
            }
            int start = v.path("startLine").asInt(chunk.getStartLine());
            int end = v.path("endLine").isNumber() ? v.path("endLine").asInt(start) : start;
            if (start < chunk.getStartLine() || end > chunk.getEndLine() || end < start) {
                badLine++;
                log.info("丢弃：行号越界 {} {}-{}（本块 {}-{}）", ruleId, start, end,
                        chunk.getStartLine(), chunk.getEndLine());
                continue;
            }

            Rule rule = byId.get(ruleId);
            Finding f = new Finding();
            f.setTaskId(task.getId());
            f.setRuleId(ruleId);
            f.setFilePath(filePath);
            f.setStartLine(start);
            f.setEndLine(end);
            f.setCodeSnippet(snippet(chunk, start, end));
            f.setViolated(true);
            f.setSeverity(orDefault(v.path("severity").asString(""), rule.getSeverity(), "MEDIUM"));
            f.setReason(v.path("reason").asString(""));
            f.setFixSuggestion(v.path("fixSuggestion").asString(""));
            double conf = v.path("confidence").isNumber() ? v.path("confidence").asDouble(0.5) : 0.5;
            f.setConfidence(Math.max(0.0, Math.min(1.0, conf)));
            findingRepository.save(f);
        }
        return new int[]{badRuleId, badLine};
    }

    /**
     * 按代码块线索挑规则。
     *
     * <p>线索为空（代码里没命中任何关键词）时给"全部规则"，但受上限约束——
     * 宁可多带几条也别漏判，这是"降低漏报"的取舍。
     */
    private List<Rule> pickRules(List<Rule> all, CodeChunk chunk) {
        List<String> hints = chunk.getCategoryHints();
        if (hints == null || hints.isEmpty()) {
            return cap(all);
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String hint : hints) {
            List<String> cats = HINT_TO_CATEGORIES.get(hint.toLowerCase(Locale.ROOT));
            if (cats != null) {
                wanted.addAll(cats);
            }
        }
        List<Rule> picked = new ArrayList<>();
        for (Rule r : all) {
            if (r.getCategory() != null && wanted.contains(r.getCategory().toLowerCase(Locale.ROOT))) {
                picked.add(r);
            }
        }
        return picked.isEmpty() ? cap(all) : cap(picked);
    }

    private static List<Rule> cap(List<Rule> list) {
        return list.size() <= MAX_RULES_PER_CHUNK ? list : list.subList(0, MAX_RULES_PER_CHUNK);
    }

    /** 送给模型的规则只留判定必需的字段，省 token、减少干扰。 */
    private Map<String, Object> brief(Rule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", r.getRuleId());
        m.put("category", r.getCategory());
        m.put("title", r.getTitle());
        m.put("severity", r.getSeverity());
        m.put("detectionHint", abbreviate(r.getDetectionHint()));
        m.put("negativeExample", abbreviate(r.getNegativeExample()));
        return m;
    }

    /** 从块代码里截出违规那几行，报告里要原样展示。 */
    private static String snippet(CodeChunk chunk, int start, int end) {
        String[] lines = chunk.getCode().split("\n", -1);
        int from = start - chunk.getStartLine();
        int to = Math.min(end - chunk.getStartLine(), lines.length - 1);
        if (from < 0 || from >= lines.length) {
            return chunk.getCode();
        }
        return String.join("\n", List.of(lines).subList(from, Math.max(from, to) + 1));
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_RULE_HINT_CHARS ? s : s.substring(0, MAX_RULE_HINT_CHARS);
    }

    private static String orDefault(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }
}
