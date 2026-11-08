package com.audit.controller;

import com.audit.service.LlmService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。一次验证 Web 层与数据源链路是否打通；建表/配置对不上时能立即暴露。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    private final JdbcTemplate jdbcTemplate;
    private final LlmService llmService;

    public PingController(JdbcTemplate jdbcTemplate, LlmService llmService) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmService = llmService;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            result.put("db", one != null ? "connected" : "failed");
        } catch (Exception e) {
            result.put("db", "failed");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("time", LocalDateTime.now().toString());
        return result;
    }

    /**
     * TODO(块2 验收后删除)：临时验证 LLM 通道能不能通。
     * 用法：GET /api/llm-test
     */
    @GetMapping("/llm-test")
    public Map<String, Object> llmTest() {
        String answer = llmService.chat(
                "你是一个助手，回答要简洁。",
                "用一句话说明 SQL 注入是什么。");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("time", LocalDateTime.now().toString());
        return result;
    }
}
