package com.audit.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域（CORS）配置：允许前端 5173 直接调用后端 8080 的 /api/**。
 *
 * <p>【功能】给浏览器的跨源请求补上 {@code Access-Control-Allow-*} 响应头。
 *
 * <p>【作用】没有它，Vue 页面在 {@code http://localhost:5173}、后端在
 * {@code http://localhost:8080}，端口不同就是"跨源"，浏览器会在请求发出前先发
 * OPTIONS 预检，后端不回允许头 → 控制台报
 * {@code blocked by CORS policy}，前端看起来就是"接口挂了但 Postman 是好的"。
 *
 * <p>【输入】浏览器发来的预检（OPTIONS）与正式请求，不用写代码调用。
 *
 * <p>【输出】响应头：
 * <pre>
 * Access-Control-Allow-Origin: http://localhost:5173
 * Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS
 * Access-Control-Allow-Headers: *
 * </pre>
 *
 * <p>【怎么用】放在 {@code config} 包下就会被自动装配，前端直接 {@code axios.post('/api/upload', form)}
 * 就行；Vite 里配了代理（{@code server.proxy}）的话其实也可以不跨域，两条路二选一。
 *
 * <p>【例子】开发期：Vue 直接 fetch {@code http://localhost:8080/api/audit/tasks}；
 * 答辩期：前端打包进 {@code resources/static}，同源，CORS 自动不生效也不影响。
 *
 * <p>注意这里用的是 {@code allowedOriginPatterns("*")} 而不是
 * {@code allowedOrigins("*")}：后者与 {@code allowCredentials(true)} 同时出现会抛异常，
 * 且 Spring 6 之后推荐用 patterns。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
