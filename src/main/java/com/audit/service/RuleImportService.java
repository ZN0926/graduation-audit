package com.audit.service;

import com.audit.model.Rule;
import com.audit.repository.RuleRepository;
import com.audit.util.JsonUtil;
import com.audit.util.RuleKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B 通道：把「已经字段化的规则源」（pylint 消息字典、bandit 插件说明）直接转成 rule 表记录。
 *
 * <p>输入：一个 JSON 数组文件，元素字段与 {@link Rule} 同名（camelCase）。
 * <p>输出：统计信息 {total, imported, skipped, byLanguage}。
 * <p>不调用大模型，temperature 无关——这条路是确定性的。
 *
 * <p>为什么需要它：A 通道（散文 → LLM 抽取）慢且要花钱，B 通道能一次把规则库灌到有货，
 * 让审计功能先跑通，再往上加质量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleImportService {

    private final RuleRepository ruleRepository;

    /** 从 JSON 文件导入规则；ruleId 已存在则跳过（可重复执行）。 */
    @Transactional
    public Map<String, Object> importFromFile(String path) throws IOException {
        Path file = Path.of(path);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("规则文件不存在：" + file.toAbsolutePath());
        }

        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonNode root = JsonUtil.MAPPER.readTree(text);
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("规则文件必须是 JSON 数组：" + path);
        }

        int imported = 0;
        int skipped = 0;
        List<String> badRows = new ArrayList<>();
        Map<String, Integer> byLanguage = new LinkedHashMap<>();

        for (JsonNode node : root) {
            String ruleId = text(node, "ruleId");
            if (ruleId.isEmpty()) {
                badRows.add("缺少 ruleId 的条目");
                skipped++;
                continue;
            }
            if (ruleRepository.existsByRuleId(ruleId)) {
                skipped++;
                continue;
            }

            Rule rule = new Rule();
            rule.setRuleId(ruleId);
            rule.setLanguage(orDefault(text(node, "language"), "unknown"));
            rule.setCategory(text(node, "category"));
            rule.setCwe(text(node, "cwe"));
            rule.setTitle(truncate(orDefault(text(node, "title"), ruleId), 200));
            rule.setDescription(text(node, "description"));
            rule.setPositiveExample(text(node, "positiveExample"));
            rule.setNegativeExample(text(node, "negativeExample"));
            rule.setSeverity(orDefault(text(node, "severity"), "MEDIUM"));
            rule.setDetectionHint(text(node, "detectionHint"));
            rule.setEvidenceQuote(truncate(text(node, "evidenceQuote"), 200));
            rule.setSourceDoc(text(node, "sourceDoc"));
            rule.setSourceRef(text(node, "sourceRef"));
            rule.setDetectability(orDefault(text(node, "detectability"), "pattern"));
            rule.setCanonicalKey(RuleKeys.canonicalKey(rule.getCwe(), rule.getCategory()));

            ruleRepository.save(rule);
            imported++;
            byLanguage.merge(rule.getLanguage(), 1, Integer::sum);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", file.getFileName().toString());
        result.put("total", root.size());
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("byLanguage", byLanguage);
        result.put("badRows", badRows);
        log.info("规则导入完成：{} 条，新增 {}，跳过 {}，分布 {}", path, imported, skipped, byLanguage);
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asString();
    }

    private static String orDefault(String s, String def) {
        return (s == null || s.isEmpty()) ? def : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
