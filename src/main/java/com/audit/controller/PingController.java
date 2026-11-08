package com.audit.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【这是什么】健康检查接口。作用只有一个：花 3 秒钟确认"Web 层 + 数据库"两条链路通不通，
 * 免得后面写了几百行代码才发现是数据源配错了。
 *
 * 【输入 → 输出】
 * <pre>
 * GET /api/ping                （不传参数）
 *   → {"status":"ok", "db":"connected", "time":"2026-11-08T19:08:34"}
 * 连不上库时：
 *   → {"status":"ok", "db":"failed", "error":"SQLException: Access denied…", "time":"…"}
 * </pre>
 *
 * 【实现要点】
 * <ul>
 *   <li>{@code SELECT 1} 是最轻量的探活：不依赖任何业务表，只要能执行就说明驱动、URL、账号、密码全对；</li>
 *   <li>数据库异常被 catch 住转成 {@code db:failed}，而不是让整个请求 500——"服务活着但库挂了"和
 *       "服务没起来"是两种故障，用 HTTP 状态码区分不出来，用 body 才能区分。</li>
 * </ul>
 *
 * 【历史】块 2 验收期间这里临时挂过一个 {@code GET /api/llm-test}，用于验证模型通道；
 * 2026-11-08 验证通过后已按计划删除，模型调用现在统一由块 4 / 块 5 的业务接口触发。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    private final JdbcTemplate jdbcTemplate;

    public PingController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
}
