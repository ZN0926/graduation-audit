package com.audit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 【这是什么】大模型连接参数的"配置容器"，本身不含任何逻辑，只是把 application.yaml 里的 llm.* 读成 Java 字段。
 *
 * 【数据从哪来】src/main/resources/application.yaml：
 * <pre>
 * llm:
 *   base-url: https://api.deepseek.com
 *   api-key: ${LLM_API_KEY:}      # 从环境变量读，代码里永远不出现明文 key
 *   model: deepseek-chat
 *   timeout-seconds: 60
 * </pre>
 *
 * 【谁用它】
 * <ul>
 *   <li>{@link LlmConfig}：启动时读 baseUrl / timeoutSeconds，用来造 RestClient（配地址和超时）；</li>
 *   <li>{@link com.audit.service.LlmService}：每次调用读 model（发给哪个模型）和 apiKey（鉴权头）。</li>
 * </ul>
 *
 * 【改了它会发生什么】改 base-url 就能把 DeepSeek 换成通义/OpenAI 兼容端点；改 model 就换模型；
 * 两者都不用改一行 Java 代码——这是"换模型不改代码"的设计，写论文时算一个工程性优点。
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** 只写到域名，例如 https://api.deepseek.com；不要把 /v1 写进来 */
    private String baseUrl;

    private String apiKey;

    /** 例如 deepseek-chat；换模型只改这里 */
    private String model = "deepseek-chat";

    /** 读超时（秒）。推理类模型慢，给 60 起步 */
    private int timeoutSeconds = 60;
}
