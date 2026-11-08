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
 * 【这是什么】健康检查接口。作用只有一个：花 3 秒钟确认"Web 层 + 数据库 + LLM 通道"三条链路通不通，
 * 免得后面写了几百行代码才发现是数据源配错了。
 *
 * 【两个接口，输入 → 输出】
 * <pre>
 * ① GET /api/ping                （不传参数）
 *    → {"status":"ok", "db":"connected", "time":"2026-11-08T17:05:49"}
 *    连不上库时：{"status":"ok", "db":"failed", "error":"SQLException: Access denied…", "time":"…"}
 *
 * ② GET /api/llm-test            （不传参数，块 2 验收用，验收后删除）
 *    → {"answer":"SQL 注入是指把恶意 SQL 拼进查询语句…", "time":"2026-11-08T17:12:03"}
 *    没配 key 时：HTTP 500，日志里 IllegalStateException: 未配置 LLM_API_KEY…
 * </pre>
 *
 * 【实现要点】
 * <ul>
 *   <li>`SELECT 1` 是最轻量的探活：不依赖任何业务表，只要能执行就说明驱动、URL、账号、密码全对；</li>
 *   <li>数据库异常被 catch 住转成 `db:failed`，而不是让整个请求 500——"服务活着但库挂了"和
 *       "服务没起来"是两种故障，用 HTTP 状态码区分不出来，用 body 才能区分。</li>
 * </ul>
 *
 * 【块 2 验收后要做的事】删掉 {@code llmTest()} 方法以及这里对 {@code LlmService} 的注入。
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
