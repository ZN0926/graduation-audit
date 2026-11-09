package com.audit.controller;

import com.audit.model.Rule;
import com.audit.repository.RuleRepository;
import com.audit.service.RuleDistillService;
import com.audit.service.RuleImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则库管理接口。
 *
 * <ul>
 *   <li>{@code POST /api/rules/import?path=...} —— B 通道：把 JSON 规则文件导入数据库</li>
 *   <li>{@code GET  /api/rules?language=python&limit=20} —— 看规则内容（抽查用）</li>
 *   <li>{@code GET  /api/rules/stats} —— 每种语言各有多少条、分类分布</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleImportService ruleImportService;
    private final RuleDistillService ruleDistillService;
    private final RuleRepository ruleRepository;

    /**
     * A 通道：从规范文档抽规则。
     *
     * <p>例：{@code curl "http://localhost:8080/api/rules/distill?docPath=docs-source/java/alibaba-p3c.txt&language=java&maxChunks=5"}
     */
    @PostMapping("/distill")
    public Map<String, Object> distill(@RequestParam String docPath,
                                       @RequestParam String language,
                                       @RequestParam(defaultValue = "5") int maxChunks) throws IOException {
        return ruleDistillService.distill(docPath, language, maxChunks);
    }

    @PostMapping("/import")
    public Map<String, Object> importRules(
            @RequestParam(defaultValue = "docs-source/python/python-rules.json") String path) throws IOException {
        return ruleImportService.importFromFile(path);
    }

    @GetMapping
    public List<Rule> list(@RequestParam(required = false) String language,
                           @RequestParam(defaultValue = "20") int limit) {
        List<Rule> all = (language == null || language.isBlank())
                ? ruleRepository.findAll()
                : ruleRepository.findByLanguage(language);
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Rule> all = ruleRepository.findAll();
        Map<String, Long> byLanguage = new LinkedHashMap<>();
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Rule r : all) {
            byLanguage.merge(orUnknown(r.getLanguage()), 1L, Long::sum);
            byCategory.merge(orUnknown(r.getCategory()), 1L, Long::sum);
        }
        result.put("total", all.size());
        result.put("byLanguage", byLanguage);
        result.put("byCategory", byCategory);
        return result;
    }

    private static String orUnknown(String s) {
        return (s == null || s.isBlank()) ? "未分类" : s;
    }
}
