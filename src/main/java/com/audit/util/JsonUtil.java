package com.audit.util;

import tools.jackson.databind.json.JsonMapper;

/**
 * 【这是什么】"把模型说的话变成程序能解析的 JSON"的清洗工具。纯静态方法，不依赖 Spring。
 *
 * 【为什么需要它】我们要求模型"只输出 JSON"，但它十次里有三次会这样回：
 * <pre>
 * 好的，以下是你要的规则列表：
 * ```json
 * [{"ruleId":"JAVA-SQLI-001", "title":"SQL 注入"}]
 * ```
 * 希望对您有帮助。
 * </pre>
 * 直接丢给 JsonMapper 会抛异常，所以要先剥掉围栏和寒暄，只留中间那段。
 *
 * 【三个方法各自干的事】
 * <pre>
 * stripFence("```json\n[1,2]\n```")     → "[1,2]"               剥掉代码围栏
 * extractArray("废话 [ {\"a\":1} ] 废话") → "[{\"a\":1}]"         取第一个 [ 到最后一个 ]
 * extractObject("说明 {\"violated\":true} 说明") → "{\"violated\":true}"  取第一个 { 到最后一个 }
 * </pre>
 *
 * 【谁用它】{@link com.audit.service.LlmService#chatForArray}、
 * {@link com.audit.service.LlmService#chatForObject}，以及块 4 / 块 5 里真正做反序列化的地方。
 *
 * 【注意】这里只做"切成合法 JSON 文本"，**不做校验**。ruleId 是否在规则表里、行号是否越界，
 * 属于块 4 / 块 5 的代码校验，不能指望这个类。
 *
 * 【工程细节】工程是 Spring Boot 4，用的是 Jackson 3，包名是 tools.jackson 而不是 com.fasterxml.jackson；
 * MAPPER 做成静态常量全局复用——Jackson 的 mapper 线程安全，每次 new 反而是浪费。
 */
public final class JsonUtil {

    /** 全局共用一个 mapper：Jackson 的 ObjectMapper 创建开销大，且线程安全 */
    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonUtil() {
    }

    /** 剥掉 ```json ... ``` 围栏，返回中间内容 */
    public static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline > 0) {
            s = s.substring(firstNewline + 1);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    /** 取第一个 [ 到最后一个 ] 之间的内容，用于解析数组 */
    public static String extractArray(String raw) {
        String s = stripFence(raw);
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }

    /** 取第一个 { 到最后一个 } 之间的内容，用于解析对象 */
    public static String extractObject(String raw) {
        String s = stripFence(raw);
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }
}
