package com.nb.tron.scanner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * TRON HTTP 客户端配置。
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Configuration(proxyBeanMethods = false)
public class TronHttpConfiguration {

    @Bean("tronHttpClient")
    public HttpClient tronHttpClient(TronScannerProperties scannerProperties) {
        return HttpClient.newBuilder()
            .connectTimeout(scannerProperties.getNode().getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }
}
