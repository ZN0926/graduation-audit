package com.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 构造带超时的 RestClient，供 LlmService 注入。
 * <p>用 JDK 自带的 SimpleClientHttpRequestFactory，不引 webflux（WebClient 需要额外依赖）。
 * <p>超时设两段：连接 10 秒、读取 timeoutSeconds 秒。不设的话模型卡住时线程会一直挂着。
 */
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
