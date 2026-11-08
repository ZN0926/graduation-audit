package com.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 【这是什么】一个"装配类"：程序启动时造出唯一一个 RestClient（HTTP 客户端），交给 Spring 容器保管。
 * 它本身不发起任何请求，只负责把"往哪发、发多久算超时"这两件事定下来。
 *
 * 【输入 → 输出】
 * <pre>
 * 输入：LlmProperties（baseUrl = https://api.deepseek.com，timeoutSeconds = 60）
 * 输出：RestClient 对象——已经绑好 baseUrl，且连接超时 10 秒、读取超时 60 秒
 * </pre>
 *
 * 【谁用它】{@link com.audit.service.LlmService} 构造时注入。全工程只有 LlmService 碰它，
 * 所以"HTTP 细节"被锁在这两个类里，业务代码不会散落着 URL 和超时数字。
 *
 * 【为什么要设超时】不设的话，模型服务端卡住时请求线程会一直挂着，表现为"页面上传后永远转圈"。
 * 60 秒是 DeepSeek 单次生成的经验上限；块 4 蒸馏时一个 chunk 要它输出十几条规则，可能偏紧，
 * 到时把 yaml 里的 timeout-seconds 调大即可，不用改代码。
 *
 * 【为什么用 RestClient 而不是 WebClient】RestClient 是 Spring 6.1 内置的同步客户端，
 * 依赖只有 spring-web（工程已有）；WebClient 要额外引 webflux，对同步审计流程没有收益。
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
