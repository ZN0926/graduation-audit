package com.audit.service;

import com.audit.config.LlmProperties;
import com.audit.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 【这是什么】整个系统唯一的"模型出口"。全工程只有这一个类会发 HTTP 请求给大模型；
 * 蒸馏（块4）、审计（块5）、复核（块6）都只是给它两段文字，拿回一段文字。
 *
 * 【它做的事，一句话】
 * <pre>
 * 输入：一段 system 提示词（定角色/定输出格式）+ 一段 user 提示词（放要处理的内容）
 * 输出：模型回复的纯文本（原样返回，不清洗 JSON 围栏——清洗是 JsonUtil 的事）
 * </pre>
 *
 * 【一次真实调用长什么样】
 * <pre>
 * 调用：chat("你是代码安全审计专家，只输出 JSON。", "判定这段代码有没有 SQL 注入：…")
 * 实际发出的 HTTP：
 *   POST https://api.deepseek.com/v1/chat/completions
 *   Authorization: Bearer sk-xxx
 *   {"model":"deepseek-chat","temperature":0,
 *    "messages":[{"role":"system","content":"你是代码安全审计专家…"},
 *                {"role":"user","content":"判定这段代码有没有 SQL 注入：…"}]}
 * 拿回的 HTTP body：{"choices":[{"message":{"content":"{\"violated\":true,...}"}}], ...}
 * 本方法返回：{"violated":true,...}   ← 只取 choices[0].message.content 这一层
 * </pre>
 *
 * 【内部按顺序做的 5 件事】
 * <ol>
 *   <li>检查 apiKey 是否为空——为空立刻抛异常，不要发一个注定 401 的请求；</li>
 *   <li>拼请求体：model 取配置、temperature 固定 0、messages 两条（system + user）；</li>
 *   <li>POST /v1/chat/completions，解出 choices[0].message.content；</li>
 *   <li>失败（网络异常 / 5xx / 返回里没有 content）就睡 1.5 秒重试，最多 2 次；</li>
 *   <li>两次都失败，抛 IllegalStateException，把最后一次的原始错误带出去。</li>
 * </ol>
 *
 * 【两个刻意的设计，答辩会被问】
 * <ul>
 *   <li><b>temperature 固定 0</b>：审计要的是"同样输入同样结论"。设成 0.7 的话，同一段代码两次跑出
 *       不同判定，误报就无法归因，论文里"误报率 12%"这种数字也就不成立。</li>
 *   <li><b>重试只做 1 次</b>：网络抖动和限流一次重试基本够；再多会让一个失败请求把整个任务拖成几分钟，
 *       而块 5 是逐 chunk 串行调用，总耗时会被放大。</li>
 * </ul>
 *
 * 【失败时会看到什么】
 * <pre>
 * 没配 key        → IllegalStateException: 未配置 LLM_API_KEY：请在运行配置的环境变量里设置
 * key 错 / 余额空 → 两次 warn 日志后 IllegalStateException: LLM 调用失败：401 Unauthorized…
 * 模型名写错      → IllegalStateException: 模型返回空内容，检查 llm.model 是否存在：xxx
 * </pre>
 *
 * 【上游怎么用它】块 4 用 {@link #chatForArray} 拿规则数组；块 5 用 {@link #chatForObject} 拿判定对象。
 * 这两个方法只是把 chat() 的结果再套一层 JsonUtil，不要在别处重复写剥围栏的代码。
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

            } catch (RestClientResponseException e) {
                // 服务端明确回了 4xx/5xx：把状态码和响应体原文打出来——模型名写错、key 无效都在这里能看见
                String detail = "HTTP " + e.getStatusCode().value() + "：" + abbreviate(e.getResponseBodyAsString());
                log.warn("LLM 调用失败，第 {}/{} 次：{}", attempt, MAX_ATTEMPTS, detail);
                lastError = new IllegalStateException(detail, e);
                // 4xx 是"请求本身不对"（key 错、模型名不存在、余额不足），重试多少次都一样，直接失败
                if (e.getStatusCode().is4xxClientError()) {
                    throw (IllegalStateException) lastError;
                }

            } catch (Exception e) {
                // 网络层（连不上、超时、SSL）：值得重试一次
                lastError = e;
                log.warn("LLM 调用失败，第 {}/{} 次：{}", attempt, MAX_ATTEMPTS, e.getMessage());
            }

            if (attempt < MAX_ATTEMPTS) {
                sleep(RETRY_BACKOFF_MS);
            }
        }
        throw new IllegalStateException("LLM 调用失败：" + (lastError == null ? "unknown" : lastError.getMessage()), lastError);
    }

    /** 响应体可能很长（含整段 HTML 报错页），日志里只留前 300 字 */
    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
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
