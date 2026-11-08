package com.audit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 配置绑定（application.yaml 的 llm.* 段）。
 * <p>yaml 里写的是 base-url / api-key / timeout-seconds，Spring 会自动转成驼峰，
 * 所以这里不需要加 @Value 或中划线注解。
 * <p>apiKey 从环境变量 LLM_API_KEY 注入（yaml 里写 ${LLM_API_KEY:}），代码里不出现明文。
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
