package com.audit.service;

import com.audit.config.LlmProperties;
import com.audit.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 大模型调用的唯一出口。上层（蒸馏/审计/复核）都只调 chat(system, user)，
 * 换句话说：整个工程里只有这一个类持有 RestClient。
 * <p>temperature 固定 0——审计要的是稳定复现，不是多样性；同输入两次结果不同的话，
 * 误报归因就没法做，论文里的数字也就不成立。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 1500L;

    private final RestClient llmRestClient;
    private final LlmProperties props;

    /**
     * 发一次对话请求。失败重试 1 次（网络抖动、限流多数一次就够），仍失败则抛异常。
     *
     * @return 模型回复的纯文本（不含 JSON 围栏清洗，由调用方用 JsonUtil 处理）
     */
    public String chat(String systemPrompt, String userPrompt) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("未配置 LLM_API_KEY：请在运行配置的环境变量里设置");
        }

        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String response = llmRestClient.post()
                        .uri("/v1/chat/completions")
                        .header("Authorization", "Bearer " + props.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);

                JsonNode root = JsonUtil.MAPPER.readTree(response);
                String content = root.path("choices").path(0).path("message").path("content").asText();
                if (content == null || content.isBlank()) {
                    throw new IllegalStateException("模型返回空内容，检查 llm.model 是否存在：" + props.getModel());
                }
                return content;
            } catch (Exception e) {
                lastError = e;
                log.warn("LLM 调用失败，第 {}/{} 次：{}", attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(RETRY_BACKOFF_MS);
                }
            }
        }
        throw new IllegalStateException("LLM 调用失败：" + (lastError == null ? "unknown" : lastError.getMessage()), lastError);
    }

    /** 期望返回 JSON 数组（规则蒸馏用） */
    public String chatForArray(String systemPrompt, String userPrompt) {
        return JsonUtil.extractArray(chat(systemPrompt, userPrompt));
    }

    /** 期望返回 JSON 对象（审计判定用） */
    public String chatForObject(String systemPrompt, String userPrompt) {
        return JsonUtil.extractObject(chat(systemPrompt, userPrompt));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
