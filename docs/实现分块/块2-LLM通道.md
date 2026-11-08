# 块 2 · LLM 通道（能把模型调通）

> ⚠️ **只实现本文件里的内容。** 其他块的文件不要动，也不要提前实现（规则见同目录 `README.md`）。
> 做法：从上往下按编号执行，每完成一步就对照「验收」打勾；验收不过就查「卡住怎么办」。


> **目标**：一个 `LlmService.chat(system, user)`，输入两段文字，稳定返回模型回复的纯文本。
> **前置**：块 1 已完成（`/api/ping` 返回 `connected`）。
> **产出**：4 个文件 + 一个临时测试接口（验完删）。
> **估时**：1 天。
> **验收标准**：调一次能拿到中文回答；把 key 改错时日志里有 **两条** 重试记录，不是一条。

---

## 1. 这一步在回答什么问题

"模型调不通，是代码的问题还是网络/额度的问题？"

后面三处都要调用模型（规则蒸馏、代码判定、结果复核）。如果三处各写一遍 HTTP 请求，
那么换模型、加重试、统计 token 都要改三处。所以**先只做一件事：把调用模型封装成可复用的一层**。

这一块做完，系统里除了这个类，不应该出现第二个 `RestClient`。

---

## 2. 涉及文件

| 文件 | 作用 |
|---|---|
| `config/LlmProperties.java` | 绑定 yaml 的 `llm.*` |
| `config/LlmConfig.java` | 造带超时的 `RestClient` |
| `service/LlmService.java` | 唯一出口：拼消息、重试、取 `content` |
| `util/JsonUtil.java` | 剥 ```json 围栏、抠 JSON |
| 临时：`PingController` 加一个 `/api/llm-test` | 验证用，**验完删** |

---

## 3. 操作步骤

### 3.1 确认 yaml 里有 llm 段

`src/main/resources/application.yaml` 应该已经有：

```yaml
llm:
  base-url: https://api.deepseek.com
  api-key: ${LLM_API_KEY:}
  model: deepseek-chat
  timeout-seconds: 60
```

`${LLM_API_KEY:}` 的意思是"从环境变量读，没有就空"。**不要把 key 写在这里**——yaml 会进 git。

### 3.2 配置 key

IDEA：`运行` → `编辑配置` → 选中 `GraduationAuditApplication` → 环境变量 → 填

```
LLM_API_KEY=sk-你的key
```

也可以 PowerShell 里 `setx LLM_API_KEY "sk-..."`，但要**重启 IDEA** 才生效。

### 3.3 `config/LlmProperties.java`

```java
package com.audit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    private String baseUrl;
    private String apiKey;
    private String model = "deepseek-chat";
    private int timeoutSeconds = 60;
}
```

> yaml 里写的是 `base-url`，这里字段是 `baseUrl`，Spring 自动把中划线转驼峰，不用加注解。

### 3.4 `config/LlmConfig.java`

```java
package com.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class LlmConfig {

    @Bean
    public RestClient llmRestClient(LlmProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(props.getTimeoutSeconds() * 1000);
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.getBaseUrl())
                .build();
    }
}
```

### 3.5 `util/JsonUtil.java`

```java
package com.audit.util;

public final class JsonUtil {

    private JsonUtil() {
    }

    /** 剥掉模型爱加的 ```json 围栏 */
    public static String stripFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    public static String extractArray(String raw) {
        String s = stripFence(raw);
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }

    public static String extractObject(String raw) {
        String s = stripFence(raw);
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }
}
```

### 3.6 `service/LlmService.java`

```java
package com.audit.service;

import com.audit.config.LlmProperties;
import com.audit.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final RestClient llmRestClient;
    private final LlmProperties props;
    private final ObjectMapper objectMapper;

    /** 发一次对话请求，失败重试 1 次 */
    public String chat(String systemPrompt, String userPrompt) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("未配置 LLM_API_KEY，请在运行配置的环境变量里设置");
        }
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String resp = llmRestClient.post()
                        .uri("/v1/chat/completions")
                        .header("Authorization", "Bearer " + props.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                JsonNode root = objectMapper.readTree(resp);
                return root.path("choices").path(0).path("message").path("content").asText();
            } catch (Exception e) {
                last = e;
                log.warn("LLM 调用失败，第 {} 次：{}", attempt, e.getMessage());
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new RuntimeException("LLM 调用失败：" + (last == null ? "unknown" : last.getMessage()), last);
    }

    public String chatForArray(String systemPrompt, String userPrompt) {
        return JsonUtil.extractArray(chat(systemPrompt, userPrompt));
    }

    public String chatForObject(String systemPrompt, String userPrompt) {
        return JsonUtil.extractObject(chat(systemPrompt, userPrompt));
    }
}
```

### 3.7 临时测试接口（验完删）

`PingController` 里加：

```java
private final com.audit.service.LlmService llmService;

public PingController(JdbcTemplate jdbcTemplate, com.audit.service.LlmService llmService) {
    this.jdbcTemplate = jdbcTemplate;
    this.llmService = llmService;
}

@GetMapping("/llm-test")
public java.util.Map<String, Object> llmTest() {
    String answer = llmService.chat("你是一个助手，回答要简洁。", "用一句话说明 SQL 注入是什么。");
    return java.util.Map.of("answer", answer);
}
```

重启后访问 `http://localhost:8080/api/llm-test`。

---

## 4. 验收（逐条打勾）

- [ ] `/api/llm-test` 返回一句通顺的中文（不是空串、不是报错）
- [ ] 把环境变量改成 `LLM_API_KEY=sk-wrong`，重启再访问：日志里出现 **两条** `LLM 调用失败，第 1 次 / 第 2 次`，然后抛 500——说明重试生效
- [ ] 改回正确的 key，`/api/llm-test` 恢复正常
- [ ] 把 `model` 改成别的（如 `deepseek-reasoner`）不改代码也能跑——说明模型名只在配置里
- [ ] 删掉 `/api/llm-test`，重启仍能正常启动

---

## 5. 卡住怎么办

| 现象 | 原因 | 修法 |
|---|---|---|
| `401 Unauthorized` | key 错/过期/欠费 | 去服务商控制台确认余额 |
| `404 Not Found` | `base-url` 结尾多了 `/v1` | 代码里已经拼了 `/v1/chat/completions`，base 只到域名 |
| `SocketTimeoutException` | 超时或网络 | 先把 `timeout-seconds` 调到 120 试 |
| `未配置 LLM_API_KEY` | 环境变量没设 / 设了没重启 | IDEA 里改完运行配置要重新 Run，热重载不生效 |
| 返回空字符串 | 模型名不存在 | 对照文档的 model 列表 |
| `SSLHandshakeException` | 代理 / 证书 | 换网络，或检查公司代理 |

---

## 6. 做完记录

`docs/03-实现过程记录.md`：「已完成」加一条（模型能调通 + 用的哪个模型），
「问题」记下任何异常。然后要块 3 的步骤。
