package com.audit.service;

import com.audit.model.DocChunk;
import com.audit.model.Rule;
import com.audit.prompt.PromptTemplates;
import com.audit.repository.RuleRepository;
import com.audit.util.JsonUtil;
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
     * @param docPath   文档路径（相对工作目录或绝对路径），必须是已转好的纯文本
     * @param language  目标语言 java / python
     * @param maxChunks 最多处理多少个条款块（先跑 5 个验证提示词效果，省钱）
     * @return 统计信息，直接能贴进论文
     */
    public Map<String, Object> distill(String docPath, String language, int maxChunks) throws IOException {
        Path path = Path.of(docPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文档不存在：" + docPath);
        }
        List<DocChunk> all = docChunker.chunk(path);
        int limit = Math.max(1, Math.min(maxChunks, all.size()));
        List<DocChunk> todo = all.subList(0, limit);
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
        rule.setCategory(text(node, "category"));
        rule.setCwe(text(node, "cwe").toUpperCase());
        rule.setSeverity(text(node, "severity").toUpperCase());
        rule.setDescription(text(node, "description"));
        rule.setDetectionHint(text(node, "detectionHint"));
        rule.setDetectability(text(node, "detectability"));
        rule.setPositiveExample(text(node, "positiveExample"));
        rule.setNegativeExample(text(node, "negativeExample"));
        rule.setEvidenceQuote(text(node, "evidenceQuote"));
        rule.setSourceDoc(docName.replaceAll("\\.(txt|md|pdf)$", ""));
        rule.setSourceRef(chunk.getClauseNo());
        rule.setRuleId(buildRuleId(language, rule.getTitle(), rule.getCwe()));
        return rule;
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
