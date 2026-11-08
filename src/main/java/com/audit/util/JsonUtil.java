package com.audit.util;

import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 清洗工具：模型返回的内容常带 ```json 围栏或前后说明文字，需要剥掉再解析。
 * <p>注意：工程是 Spring Boot 4，用的是 Jackson 3，包名是 tools.jackson 而不是 com.fasterxml.jackson。
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
