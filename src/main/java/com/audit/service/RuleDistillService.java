package com.audit.service;

import com.audit.model.DocChunk;
import com.audit.model.Rule;
import com.audit.prompt.PromptTemplates;
import com.audit.repository.RuleRepository;
import com.audit.util.JsonUtil;
import com.audit.util.RuleKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A 通道：把规范文档（书/手册）里的条款，抽成结构化规则并入库。
 *
 * <p>流程（四道闸，论文里就是这张图）：
 * <pre>
 * 文档 .txt ──DocChunker──▶ 条款块 ──LLM(temperature=0)──▶ 候选规则
 *                                     │
 *                    ┌────────────────┴─────────────────┐
 *                    ▼                                  ▼
 *          闸1 证据回查：evidenceQuote 必须在原文里      闸2 字段校验：category/cwe/severity/detectability 在枚举内
 *          逐字出现（去空白后子串匹配），找不到 → 丢弃      缺字段或非法 → 丢弃
 *                    │                                  │
 *                    └────────────────┬─────────────────┘
 *                                     ▼
 *                          闸3 去重：同批次 (cwe+title)、与库内 ruleId
 *                                     ▼
 *                          闸4 统计：入库几条、证据不足丢几条、重复丢几条
 * </pre>
 *
 * <p>为什么必须有闸 1：模型非常擅长"合理编造"——给一条「禁止 SQL 拼接」，它能编出
 * 「禁止用 StringBuilder 拼 SQL」再配一句"原文"看似天衣无缝。唯一的硬办法是把它的
 * evidenceQuote 拿回原文做子串匹配：编的句子在原文里不存在，直接拦下。
 * 这一条是整篇论文"降低幻觉"最有说服力的证据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleDistillService {

    private static final Set<String> CATEGORIES = Set.of(
            "injection", "crypto", "auth", "path-traversal", "deserialization",
            "secrets", "config", "error-handling", "logging", "naming", "style");

    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private static final Set<String> DETECTABILITIES = Set.of("pattern", "semantic", "manual");

    private final DocChunker docChunker;
    private final LlmService llmService;
    private final RuleRepository ruleRepository;

    /**
     * 蒸馏一个文档。
     *
     * @param docPath    文档路径（相对工作目录或绝对路径），必须是已转好的纯文本
     * @param language   目标语言 java / python
     * @param startChunk 从第几块开始（0 起）。用来跳过前几章"命名/注释/格式"这类非安全条款，
     *                   直接定位到「四、安全规约」，不必每次从第 1 块重跑
     * @param maxChunks  从 startChunk 起最多处理多少块（先跑 5 个验证提示词效果，省钱）
     * @return 统计信息，直接能贴进论文
     */
    public Map<String, Object> distill(String docPath, String language, int startChunk, int maxChunks)
            throws IOException {
        Path path = Path.of(docPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文档不存在：" + docPath);
        }
        List<DocChunk> all = docChunker.chunk(path);
        int from = Math.max(0, Math.min(startChunk, Math.max(0, all.size() - 1)));
        int to = Math.min(all.size(), from + Math.max(1, maxChunks));
        List<DocChunk> todo = all.subList(from, to);
        String docName = path.getFileName().toString();

        int candidates = 0;
        int saved = 0;
        int droppedEvidence = 0;
        int droppedInvalid = 0;
        int droppedDuplicate = 0;
        int callFailed = 0;
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Map<String, Integer> byDetectability = new LinkedHashMap<>();
        List<String> savedIds = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();

        for (int i = 0; i < todo.size(); i++) {
            DocChunk chunk = todo.get(i);
            String system = PromptTemplates.DISTILL_FROM_TEXT_SYSTEM;
            String user = PromptTemplates.buildDistillUser(
                    language, chunk.getSection(), chunk.getLevel(), chunk.getClauseNo(), chunk.getText());

            JsonNode array;
            try {
                // chatForArray 已剥掉代码围栏并截出最外层 [...]，这里再解析成树
                array = JsonUtil.MAPPER.readTree(llmService.chatForArray(system, user));
            } catch (RuntimeException e) {
                callFailed++;
                log.warn("第 {}/{} 块调用失败（条款 {}）：{}", i + 1, todo.size(), chunk.getClauseNo(), e.getMessage());
                continue;
            }
            if (array == null || !array.isArray()) {
                callFailed++;
                log.warn("第 {}/{} 块返回的不是 JSON 数组", i + 1, todo.size());
                continue;
            }

            String normalizedSource = stripWhitespace(chunk.getText());
            for (JsonNode node : array) {
                candidates++;
                Rule rule = toRule(node, language, docName, chunk);

                String reason = validate(rule, normalizedSource);
                if (reason != null) {
                    if ("evidence".equals(reason)) {
                        droppedEvidence++;
                        log.info("证据回查失败，丢弃：「{}」quote={}", safe(rule.getTitle()), safe(rule.getEvidenceQuote()));
                    } else {
                        droppedInvalid++;
                        log.info("字段校验失败（{}），丢弃：「{}」", reason, safe(rule.getTitle()));
                    }
                    continue;
                }

                String key = safe(rule.getCwe()) + "|" + safe(rule.getTitle());
                if (!seenKeys.add(key)) {
                    droppedDuplicate++;
                    continue;
                }
                if (ruleRepository.existsByRuleId(rule.getRuleId())) {
                    droppedDuplicate++;
                    continue;
                }

                ruleRepository.save(rule);
                saved++;
                savedIds.add(rule.getRuleId());
                byCategory.merge(rule.getCategory(), 1, Integer::sum);
                byDetectability.merge(rule.getDetectability(), 1, Integer::sum);
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("doc", docName);
        stats.put("language", language);
        stats.put("chunksTotal", all.size());
        stats.put("chunksFrom", from);
        stats.put("chunksProcessed", todo.size());
        stats.put("candidates", candidates);
        stats.put("saved", saved);
        stats.put("droppedEvidence", droppedEvidence);
        stats.put("droppedInvalid", droppedInvalid);
        stats.put("droppedDuplicate", droppedDuplicate);
        stats.put("callFailed", callFailed);
        stats.put("byCategory", byCategory);
        stats.put("byDetectability", byDetectability);
        stats.put("savedIds", savedIds.size() <= 20 ? savedIds : savedIds.subList(0, 20));
        log.info("A 通道蒸馏完成：处理 {} 块，候选 {}，入库 {}，证据丢弃 {}，非法 {}，重复 {}",
                todo.size(), candidates, saved, droppedEvidence, droppedInvalid, droppedDuplicate);
        return stats;
    }

    /** JSON 节点 → Rule（先填，校验在 {@link #validate} 里做）。 */
    private Rule toRule(JsonNode node, String language, String docName, DocChunk chunk) {
        Rule rule = new Rule();
        rule.setLanguage(language);
        rule.setTitle(text(node, "title"));
        rule.setCategory(normalizeCategory(text(node, "category")));
        rule.setCwe(normalizeCwe(text(node, "cwe")));
        rule.setSeverity(normalizeSeverity(text(node, "severity")));
        rule.setDescription(text(node, "description"));
        rule.setDetectionHint(text(node, "detectionHint"));
        rule.setDetectability(normalizeDetectability(text(node, "detectability")));
        rule.setPositiveExample(text(node, "positiveExample"));
        rule.setNegativeExample(text(node, "negativeExample"));
        rule.setEvidenceQuote(text(node, "evidenceQuote"));
        rule.setSourceDoc(docName.replaceAll("\\.(txt|md|pdf)$", ""));
        rule.setSourceRef(chunk.getClauseNo());
        rule.setRuleId(buildRuleId(language, rule.getTitle(), rule.getCwe()));
        rule.setCanonicalKey(RuleKeys.canonicalKey(rule.getCwe(), rule.getCategory()));
        return rule;
    }

    // ------------------------------------------------------------ 字段归一
    //
    // 模型不是编译器：它会把 CWE 写成 "CWE-330（弱随机）"、"CWE330"、"330"，
    // 把 severity 写成 "高"、"High"、"critical"，把 detectability 写成
    // "pattern/dataflow"。硬校验会把一半好规则扔掉（实测 20 条候选丢 9 条），
    // 所以先做"宽容归一"，再把明显不合法的交给 validate 拦下。

    /** 从任意写法里抠出标准 CWE 编号；抠不出返回原串（交给 validate 拦）。 */
    static String normalizeCwe(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().toUpperCase().replaceAll("\\s+", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CWE[-_:]?(\\d{1,4})")
                .matcher(s.replace("－", "-"));
        if (m.find()) {
            return "CWE-" + String.format("%03d", Integer.parseInt(m.group(1)));
        }
        if (s.matches("\\d{1,4}")) {
            return "CWE-" + String.format("%03d", Integer.parseInt(s));
        }
        return raw.trim().toUpperCase();
    }

    /** 中文/别名 → 枚举内的小写分类。 */
    static String normalizeCategory(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT).replace("_", "-");
        return switch (s) {
            case "注入", "sql注入", "command-injection", "sqli", "xss", "inject" -> "injection";
            case "凭证", "密码", "硬编码", "credential", "credentials", "password" -> "secrets";
            case "加密", "随机数", "cryptography", "random" -> "crypto";
            case "路径", "path", "path-traversal" -> "path-traversal";
            case "鉴权", "认证", "授权", "authorization", "authentication" -> "auth";
            case "配置", "configuration", "config" -> "config";
            case "日志", "logging" -> "logging";
            case "错误处理", "异常处理", "error-handling", "exception" -> "error-handling";
            case "命名", "naming" -> "naming";
            case "风格", "style" -> "style";
            case "反序列化", "deserialization" -> "deserialization";
            default -> s;
        };
    }

    static String normalizeSeverity(String raw) {
        if (raw == null) {
            return "MEDIUM";
        }
        String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (s) {
            case "HIGH", "高", "高危", "CRITICAL", "严重" -> "HIGH";
            case "LOW", "低", "低危" -> "LOW";
            case "INFO", "提示", "信息" -> "INFO";
            case "MEDIUM", "中", "中危" -> "MEDIUM";
            default -> "MEDIUM";
        };
    }

    static String normalizeDetectability(String raw) {
        if (raw == null) {
            return "semantic";
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.contains("pattern") || s.contains("文本") || s.contains("正则")) {
            return "pattern";
        }
        if (s.contains("dataflow") || s.contains("数据流") || s.contains("污点")) {
            return "dataflow";
        }
        if (s.contains("manual") || s.contains("人工") || s.contains("不可")) {
            return "manual";
        }
        if (s.contains("semantic") || s.contains("语义")) {
            return "semantic";
        }
        return "semantic";
    }

    /**
     * 四道闸里的前两道。
     *
     * @return null 表示通过；否则返回失败代码（evidence / title / category / cwe / severity / detectability）
     */
    private static String validate(Rule rule, String normalizedSource) {
        if (rule.getTitle() == null || rule.getTitle().isBlank()) {
            return "title";
        }
        if (rule.getTitle().length() > 200) {
            return "title";
        }
        if (!CATEGORIES.contains(rule.getCategory())) {
            return "category";
        }
        if (rule.getCwe() == null || !rule.getCwe().matches("CWE-\\d{3}")) {
            return "cwe";
        }
        if (!SEVERITIES.contains(rule.getSeverity())) {
            return "severity";
        }
        if (!DETECTABILITIES.contains(rule.getDetectability())) {
            return "detectability";
        }
        if (rule.getDescription() == null || rule.getDescription().isBlank()) {
            return "description";
        }
        String quote = rule.getEvidenceQuote();
        if (quote == null || quote.isBlank()) {
            return "evidence";
        }
        // 闸 1：证据回查。原文与引用都去掉所有空白字符再比，避免换行/缩进造成的假失败。
        if (!normalizedSource.contains(stripWhitespace(quote))) {
            return "evidence";
        }
        return null;
    }

    /** ruleId 用 (语言 + 标题 + CWE) 的 8 位哈希，稳定、可重复、跨机器一致。 */
    private static String buildRuleId(String language, String title, String cwe) {
        String seed = language + "|" + safe(title) + "|" + safe(cwe);
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return language.toUpperCase() + "-DOC-" + sb;
        } catch (Exception e) {
            return language.toUpperCase() + "-DOC-" + Math.abs(seed.hashCode());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asString().trim();
    }

    private static String stripWhitespace(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
