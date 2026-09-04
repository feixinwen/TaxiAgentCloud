package com.fancy.taxiagent.order.amap.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AmapProperties.class)
public class AmapConfig {

    private static final Logger log = LoggerFactory.getLogger(AmapConfig.class);

    @Bean("amapRestClient")
    public RestClient amapRestClient(RestClient.Builder builder, AmapProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeout());
        requestFactory.setReadTimeout(properties.getTimeout());

        return builder
                .baseUrl(properties.getUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    long startNs = System.nanoTime();
                    String maskedUri = maskKey(request.getURI());
                    try {
                        var response = execution.execute(request, body);
                        long costMs = (System.nanoTime() - startNs) / 1_000_000;
                        if (log.isDebugEnabled()) {
                            log.debug("[AMAP] {} {} -> {} ({}ms)", request.getMethod(), maskedUri,
                                    response.getStatusCode(), costMs);
                        }
                        return response;
                    } catch (Exception e) {
                        long costMs = (System.nanoTime() - startNs) / 1_000_000;
                        log.warn("[AMAP] {} {} failed ({}ms): {}: {}", request.getMethod(), maskedUri,
                                costMs, e.getClass().getSimpleName(), e.getMessage());
                        throw e;
                    }
                })
                .build();
    }

    private static String maskKey(URI uri) {
        if (uri == null || uri.toString().isBlank()) {
            return uri == null ? null : uri.toString();
        }
        return uri.toString().replaceAll("([?&]key=)([^&]+)", "$1***");
    }
}
